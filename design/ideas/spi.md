# A UI-fiber SPI of its own, which no app ever calls

Today `BlockingExecutor` is both the SPI a strategy implements and, through `BlockingDialogs`' 1:1
static delegates, the API an app calls; `StrategySupport` is the strategy-neutral half every
strategy must remember to call. Split the audiences:

- **`vaadin-fibers-spi`** (name open, `Q_module_names`) — a new Gradle subproject holding only the
  SPI type, `…spi.UIFiber` (`Q_spi_name`). Minimal surface, exhaustive javadoc: every
  precondition, every guarantee, every non-guarantee. No app calls it, ever.
- **`vaadin-blocking-dialogs`** — the app-facing API, built on the SPI. The low-level primitives
  (`runLater`, `runUntilPark`, `parkAndAwait`, `accessSynchronously`, …) in one class, the helpers
  (`showAndAwait`) in `BlockingDialogs`; no method exists twice. Everything strategy-neutral that
  `StrategySupport` holds today — `CurrentInstance`s, the in-fiber flag, the `ErrorHandler`
  routing, the anchor watch, the inline branches — moves in here, *around* the SPI calls, so a
  strategy can't forget any of it.
- **Strategies** depend on the SPI module only. Loom never sees `StrategySupport`, nor the API.

```
app ──► vaadin-blocking-dialogs ──► vaadin-fibers-spi ◄── vaadin-blocking-dialogs-loom
                                                     ◄── …-session-unlock
```

**Where the brainstorm stands** (paused 2026-09-24):

- Settled: the three-layer split above; the strategy owns `park()`, so it can fiddle with the
  locks; the SPI is `runUntilFirstPark` + a strategy-owned `Completable` (sketch below); the
  contract is strict — no UIDL and no other request until the first park, the fiber continuing
  elsewhere afterwards; Hacky is rejected.
- Postponed by Martin: the probe of the raw-lock release for background threads.
- Next: `Q_lock_between_parks`, `Q_complete_threading`, `Q_completable_param`, then the names.

Graduates when the modules land: the founding reasoning to a `D_` (it rewrites
`D_pluggable_strategy`'s cost paragraph and `D_spi_exactly_one`), the layering to the AGENTS.md
module map, the contract to the SPI's own javadoc.

## The strategies the SPI must fit

| | Loom | Background threads |
|---|---|---|
| Mechanism | the fiber is a virtual thread; each continuation runs as a session access task | the fiber is a worker platform thread; holds the lock, releases it around a park (`blocking-strategy-session-unlock.md`, Matthias's *Release Lock*) |
| Lock released on IO | **yes** — every unmount (`R_vt_unmount_releases_lock`), unless `loom-holds-the-lock-across-bare-unmounts.md` lands | no |
| Until next park, no UIDL meanwhile | yes: mount the first continuation on the caller, or queue it and let the drain before the real release run it (`R_unlock_pushes`) | not as sketched: the caller's release pushes; maybe with a raw-lock release, see below |
| Price | JDK 24+, `--add-opens`, reflection, the lock wrapper, platform request threads | a platform thread per open dialog; the handoff window of `D_input_exclusion` |

### Rejected: Hacky — the fiber on the request thread

Settled with Martin: **Hacky goes**, and the contract stays strict. Hacky ran the fiber on the
request thread, a park releasing the session lock and blocking that thread. Three reasons, each
enough — the one-line why-not for the `D_` at graduation:

1. **The answer never arrives.** Releasing the lock does push the dialog through Atmosphere
   (`R_unlock_pushes`), so it renders; but the client keeps one request outstanding and an async
   push never ends it, so the answering click waits in the browser until the parked request
   responds — which waits for that click (`R_async_push_no_response`, verified on `WEBSOCKET` and
   `WEBSOCKET_XHR`).
2. **It can't return at the park.** The fiber *is* the caller's stack, so `runUntilFirstPark` could
   only return when the fiber ends. Relaxing the contract to "the first park *or* the end" leaves
   callers unable to rely on either — the next line may run before the dialog or after the answer
   — and it breaks SB-Emulators: its epilogue would run only after the modal closed, the browser
   showing un-reconciled state all along, which Swing's repainting secondary loop never does.
3. **It can't run under Karibu.** The test thread *is* the request thread: the modal's park blocks
   it, and nobody is left to click OK. SB-Emulators' 40 modal-resume tests would hang, and could only
   run through the scripted strategy, never the real one.

### What `runUntilPark` really promises: no UIDL until the park

Martin: `runUntilPark` is about **postponing the UIDL sent to the browser until the fiber's first
park** — the browser never sees the caller's changes without the fiber's first segment, nor the
state between the two. Loom gives it by construction: the carrier *is* the caller and never
releases the lock; the park merely unmounts, and the request's own response carries everything
(except where the first segment unmounts on IO, `R_vt_unmount_releases_lock` — the continuation
returns to the caller before the park). So the contract line is: *between the call and its return
nothing reaches the browser, and no other request runs; after the return the fiber continues
elsewhere — on the strategy's own thread, never on the caller's.*

Background threads, as the session-unlock idea sketches them (its `Q_modal_gap`: the caller
releases its holds, waits for the worker's first park or end, re-takes them), break it: the
caller's release is an ultimate `VaadinSession.unlock()`, which pushes the listener's half-done
state (`R_unlock_pushes`). So as sketched they can't do `runUntilPark`.

**Candidate fix, to probe — release the raw lock, not the session.** `VaadinSession.unlock()` is
what drains and pushes; the `ReentrantLock` underneath knows nothing of UIDL. `R_unlock_pushes`
already records that a release through AQS (`Condition.await()`) "drains nothing and pushes
nothing" **[src]**. So:

1. the caller releases its holds on `session.getLockInstance()` directly — no drain, no push;
2. the worker `session.lock()`s, runs the first segment, and at its *first* park releases the raw
   lock as well and signals the caller — no push there either;
3. the caller re-takes its holds and returns; the request's normal response carries the listener's
   changes, the first segment's and the epilogue's together, in one UIDL;
4. every *later* park of the worker releases through `VaadinSession.unlock()` and pushes, as the
   session-unlock idea has it — nobody else is going to respond for it then.

The answer click then arrives as an ordinary request. Holes to probe:

- **`Q_handoff_gap`** — while the raw lock is free, another thread can take it: another tab's
  request, or a background `ui.access` that finds the lock free and drains the whole queue
  (`R_unlock_pushes`, last bullet). Its ultimate unlock pushes the half-done state and runs a
  request in the gap — both halves of the promise broken, if only in a window of one first segment.
  A fair handoff (the worker is already queued when the caller releases) narrows it but the
  session lock is non-fair (`Q_lock_fairness` in the session-unlock idea). Close it, or state it?
- **`Q_raw_release_state`** — is anything besides the drain and the push tied to
  `VaadinSession.unlock()`: `CurrentInstance` bookkeeping, the session's `lockInstance` checks in
  `VaadinService`, Karibu's lock assumptions? The worker's `hasLock()` must be true while it runs.
- The same UI can't send a second click into the gap regardless: the browser is still waiting for
  the response of the very request doing the waiting (`R_async_push_no_response`, first bullet).

If the fix holds, background threads keep `runUntilPark`, and with it the "`runLater` falls out of
`runUntilPark`" derivation below. If it doesn't, `runUntilPark` becomes an optional SPI capability
(`Q_optional_run_until_park`) — and `runLater` needs its own primitive, since `D_input_exclusion`
wants the same first-park wait before the response.

## `runLater` falls out of `runUntilPark`

The access tasks pending at the ultimate unlock run *before* the lock is really released
(`R_unlock_pushes`, the last-but-one bullet). So

```java
runLater(body) == session.access(() -> runUntilPark(body))
```

— the new fiber starts after the listener returns (or, inside a fiber, at its park, since that
releases the lock), runs until its first park, and only then is the lock really released: exactly
`runLater`'s promise and `D_input_exclusion`. Loom does today's `runLater` in effect this way
already: its first continuation is queued as an access task. So the lowest common denominator is
not `runLater` with no guarantees, but **a handoff: "run this new fiber until it parks or ends,
then give me the lock back"** — each strategy its own way:

- **Loom** — mount the first continuation on the calling thread (today's `mountHere`).
- **Background threads** — release the raw lock holds (no push), start the worker, wait for its
  first park or end, re-take the holds — if the raw-release fix above holds.

`runUntilPark` inside a fiber is inline, and needs no strategy at all.

## The SPI sketch

### Martin's cut

Two SPI methods; the thing a fiber parks on is the strategy's own:

```java
<R> Completable<R> newCompletable();
void runUntilFirstPark(Runnable runnable, Completable<?> completable);   // UI thread; returns at the first park

interface Completable<R> {
    void complete(R result);
    R park();
}
```

### Why the strategy owns `park()`

**Settled:** the strategy owns `park()`, so it can fiddle with the locks. An earlier cut here had
`runUntilFirstPark(Runnable)` + `park(Future<?>)`; owning the wake-up too wins:

- **Background threads get it almost for free from a `Condition`** of the session's
  `ReentrantLock`: `complete()` = store + `signal()`, `park()` = `await()`. `await()` releases the
  whole hold count through AQS and restores it on wake — the raw release, no drain, no push
  (`R_unlock_pushes`) — so the session-unlock idea's hold counting (`Q_reentrancy` there)
  disappears. `runUntilFirstPark` is the same move one level up: the caller `await()`s a handoff
  condition, the worker `signal()`s it at its first park. Later parks push first (`ui.push()`
  before `await()`, the unverified bullet of `R_unlock_pushes`).
- **The wake contract is the SPI's own**, not `CompletableFuture`'s — no async callbacks on
  arbitrary threads, no `cancel(mayInterruptIfRunning)`, no `obtrudeValue`.
- **The strategy sees every park**, which is the registry `session-destroy-ends-bare-parks.md`
  wants.

### What I'd change

- **A failure path.** `complete(R)` alone can't end a wait whose anchor died, nor one whose job
  failed: add `fail(Throwable)`, and let `park()` throw like `Future.get()` —
  `ExecutionException` around the cause, `InterruptedException` — which the API already unwraps
  into the `parkAndAwait` contract.
- **Drop `runUntilFirstPark`'s `Completable` parameter** — or say what it is for
  (`Q_completable_param`). If it signals the fiber's end, the API can do that by wrapping the
  runnable; if it is the completable of the first park, the strategy can't rely on the first park
  being on it.
- **Pass the `VaadinSession`, explicitly.** Implementors *do* know Vaadin, and must: loom queues
  continuations with `session.access` and needs its servlet's lock wrapper; background threads
  need `session.getLockInstance()`, and `VaadinSession.unlock()` for the pushing release. A
  Vaadin-free SPI would buy nothing. The session rather than the UI: it is the lock's scope, and a
  fiber follows its anchor to another UI on F5 (`R_preserve_migration`). Explicit rather than
  `VaadinSession.getCurrent()`, so the contract names it.

```java
package com.github.mvysny.blockingdialogs.spi;

public interface UIFiberStrategy {                       // Q_spi_name
    /**
     * Runs fiber as a new UI fiber and returns once it first parks or ends, holding the lock again.
     * Called holding the lock of session, outside any fiber. Between the call and its return
     * nothing reaches the browser, and no other request runs. A fiber that parked continues
     * elsewhere once woken - on the strategy's own thread, never on the caller's.
     */
    void runUntilFirstPark(VaadinSession session, Runnable fiber);

    /** A one-shot wake-up a fiber of session parks on. */
    <R> Completable<R> newCompletable(VaadinSession session);
}

public interface Completable<R> {
    /** Called holding the session lock; the first complete() or fail() wins. */
    void complete(R value);
    void fail(Throwable cause);
    /**
     * Called once, by a fiber of the session; returns at once if already completed. The lock is
     * released meanwhile and held again on return.
     */
    R park() throws ExecutionException, InterruptedException;
}
```

### The API on top

| API | on the SPI |
|---|---|
| `runLater(body)` | `session.access(() -> spi.runUntilFirstPark(session, wrap(ui, body)))` — see above |
| `runUntilPark(body)` | inside a fiber: `wrap(ui, body)` inline; else `spi.runUntilFirstPark(session, wrap(ui, body))` |
| `parkAndAwait(anchor, future)` | `c = spi.newCompletable(session)`; `future.whenComplete` → `session.access` → `c.complete` / `c.fail`; the anchor watch → `c.fail(new CancellationException())`; `unwrap(c::park)`, rebind the UI |
| `showAndAwait(confirmDialog)` | the dialog's listeners call `c.complete(outcome)` directly — they hold the lock; no future at all |
| `accessSynchronously` from a background thread | no fiber there: `access` + a plain `CompletableFuture.get()` on that thread |
| `isInUIFiber`, `checkInUIFiber`, `access` | API only |

`wrap(ui, body)` is today's `runUIFiber`: the `CurrentInstance`s, the in-fiber flag, the
`ErrorHandler`. The strategies never see it.

### Per strategy

- **Loom** — `Completable` wraps a `CompletableFuture`; `park()` = `get()`, which unmounts;
  `complete()` unparks, and the continuation queues as an access task. `runUntilFirstPark` is
  today's `mountHere` start.
- **Background threads** — the `Condition`s above; `runUntilFirstPark` = start the worker,
  `handoff.await()`. A fiber that ends without parking must also release raw and signal, or its
  final `VaadinSession.unlock()` pushes the half-done state before the caller's response.
- **Scripted (tests)** — `park()` plays the next scripted user action, which completes it, and
  returns; `runUntilFirstPark` runs the fiber inline to its end.

### The epilogue before every UIDL

Found while rejecting Hacky, and worth keeping on its own: SB-Emulators reconciles Swing state onto
Vaadin in an epilogue after the listener. In `ui.beforeClientResponse(...)` instead, it would run
before every UIDL, a push's included (`R_unlock_pushes`), under any strategy — and that need would
stop depending on `runUntilPark` (`Q_epilogue_hook`).

## Open questions

- **`Q_spi_name`** — `UIFiber` names the unit, but the SPI type is the thing that *runs* units:
  `UIFiberStrategy`, `UIFiberScheduler`, `UIFiberRuntime`? And the app-facing primitives class: if
  it holds only statics now (the SPI lookup moves behind it), `UIFibers.runLater(() -> …)` reads
  well and drops the `BlockingExecutor.get().` prefix that made the split costly.
- **`Q_module_names`** — `vaadin-fibers-spi` vs `vaadin-blocking-dialogs-spi` (matches the other
  artifactIds and the `configureMavenCentral` rule); package `…blockingdialogs.spi` either way?
- **`Q_run_later_derived`** — two cracks in `runLater == access(runUntilPark)`:
  - loom on a *virtual* drainer (a virtual request thread, a background virtual thread's
    `ui.access`) can't mount, so its `runUntilFirstPark` must fall back to today's handoff and return
    before the park. Is "returns once it parks or ends, *or* at once where the strategy can't" an
    honest SPI contract, or does the API keep loom's refusal for `runUntilPark` and accept the
    fallback only under `runLater`?
  - background threads would release the holds from *inside* the unlock's drain. The raw release
    suits that too — `VaadinSession.unlock()` there would re-enter the drain — but the drain's own
    push then runs after the worker's first park, not before: check that is the push we want.
- **`Q_lock_between_parks`** — does the SPI promise "the fiber holds the lock except inside
  `park`" (`StrategySupport`'s class doc says a strategy owes it)? Loom breaks it at every IO
  unmount. Either the promise holds and loom must fix it
  (`loom-holds-the-lock-across-bare-unmounts.md`), or the SPI states it as a non-guarantee and
  **One API, any strategy** weakens to "the same code runs, not always atomically".
- **`Q_wrapping`** — the API wraps the body before handing it over, so the wrapper runs on the
  fiber's thread. Anything the wrapper must do on the *caller's* thread first (capturing the UI,
  `checkLockedUI`) happens in the API before `runUntilFirstPark`; confirm nothing in `runUIFiber` or
  `awaitAnchored` needs strategy internals.
- **`Q_completable_param`** — what `runUntilFirstPark`'s `Completable` parameter in Martin's cut
  is for; dropped in the sketch until it has a job.
- **`Q_epilogue_hook`** — should SB-Emulators reconcile in `ui.beforeClientResponse(...)` instead
  of after the listener? It then holds for every UIDL, pushes included, whatever the strategy.
  Does the API offer a "before every park" hook for it, or is Vaadin's own hook enough?
- **`Q_complete_threading`** — `complete()` / `fail()` only under the session lock, the API
  bridging a background job's completion through `session.access`? The `Condition` needs it; loom
  wouldn't care. The lock also orders the wake-up: the fiber resumes only after the completing
  request lets go.
- **`Q_future_cancel`** — the anchor dies: today the app's future is cancelled
  (`D_anchored_wait`). With a `Completable`, the API fails it; does it still cancel the app's
  future too, so a background job sees the wait is gone?
