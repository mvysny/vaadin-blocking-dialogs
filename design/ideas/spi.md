# A UI-fiber SPI of its own, which no app ever calls

Today `BlockingExecutor` is both the SPI a strategy implements and, through `BlockingDialogs`' 1:1
static delegates, the API an app calls; `StrategySupport` is the strategy-neutral half every
strategy must remember to call. Split the audiences:

- **`vaadin-uifiber-spi`** (`Q_module_names`) — a Gradle subproject holding only the SPI type,
  `…blockingdialogs.uifiber.spi.UIFiberRunnerSpi` (`Q_spi_name`), and its `Completable`. Minimal
  surface, exhaustive javadoc: every precondition, every guarantee, every non-guarantee. No app calls it, ever.
- **`vaadin-blocking-dialogs`** — the app-facing API, built on the SPI. The low-level primitives
  (`runLater`, `runUntilPark`, `parkAndAwait`, `accessSynchronously`, …) in one class, the helpers
  (`showAndAwait`) in `BlockingDialogs`; no method exists twice. Everything strategy-neutral that
  `StrategySupport` holds today — `CurrentInstance`s, the in-fiber flag, the `ErrorHandler`
  routing, the anchor watch, the inline branches — moves in here, *around* the SPI calls, so a
  strategy can't forget any of it.
- **Strategies** depend on the SPI module only. Loom never sees `StrategySupport`, nor the API.

```
app ──► vaadin-blocking-dialogs ──► vaadin-uifiber-spi ◄── vaadin-uifiber-loom
                                                      ◄── vaadin-uifiber-session-unlock
```

**Where it stands** (2026-09-25): `vaadin-uifiber-spi` exists, the interfaces with their javadoc
— the contract's home now, over the sketch below. Nothing uses it yet: the API and loom still speak
`BlockingExecutor`. The loom module is renamed `vaadin-uifiber-loom`, package `…blockingdialogs.uifiber.loom`.

- Settled:
  - the three-layer split above; the strategy owns `park()`, so it can fiddle with the locks;
  - the SPI is `runUntilFirstPark(session, fiber)` + a strategy-owned `Completable` (sketch below);
  - the contract is strict — no UIDL and no other request until the first park, the fiber
    continuing elsewhere afterwards;
  - **every `Completable.park()` releases the session lock, nothing else does**; the "first park"
    is the first `Completable.park()`. Loom breaks it at IO unmounts and is accepted as "slightly
    broken" until it holds the lock across them (`Q_lock_between_parks`);
  - `complete()` / `fail()` run under the session lock, the API bridging lock-less callers
    through `session.access`;
  - only the API calls `fail()`: the anchor's death (`CancellationException`, and the app's future
    is cancelled too — `Q_future_cancel`) and the app's future failing; a user's Cancel is an answer;
  - `runUntilFirstPark` takes no `Completable` (`Q_completable_param`); Hacky is rejected;
  - the SPI type is `UIFiberRunnerSpi`, its implementations `LoomUIFiberRunner` and the like
    (`Q_spi_name`);
  - the modules are `vaadin-uifiber-spi`, `vaadin-uifiber-loom`, … (`Q_module_names`);
  - **a fiber keeps one thread**: `Thread.currentThread()` is the same from `body`'s start to its
    end, across every park, so its thread-locals survive a dialog — `CurrentInstance`, the in-fiber
    flag, the app's MDC or transaction context. Which thread is the runner's, never promised to be
    the caller's, nor the fiber's alone: a runner may nest another fiber inside a `park()` if it
    ends before the park returns, as the scripted one does. Rules out a runner mounting a raw
    `Continuation` on any free carrier — it would strand `UI.getCurrent()` anyway. The app-facing
    half ("code after `confirm()` sees the thread-locals it saw before") goes into `BlockingDialogs`'
    docs with the API rebuild;
  - `isInUIFiber` stays the API's own `ThreadLocal`, sound by the rule above. Rejected: an SPI
    `isUIFiberThread()` — a runner's "one of my threads" is broader than "inside `body`" (the
    wrapper's error path, an idle worker), the scripted runner would only copy the API's flag, and
    every SPI method is one more thing a runner can get subtly different;
  - **a wake-up is an access task** (`Q_settle_woken`, `Q_nested_run_later`): a woken fiber runs to
    its next park or end at the session's next drain, before any UIDL and any other request; every
    real release of the lock — the caller's unlock, every later park — drains first. Cascades
    settle in the same drain; `runUntilFirstPark` still returns at its own fiber's first park.
- Postponed by Martin: the probe of the raw-lock release for background threads.
- Next: `Q_run_later_derived`, `Q_wrapping`, `Q_epilogue_hook`; the app-facing class name, and the
  prose rename "strategy" → "runner". Then port loom onto the SPI and rebuild the API on it.

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
  condition, the worker `signal()`s it at its first park. Later parks drain and push first
  (`Q_nested_run_later`; `ui.push()` before `await()`, the unverified bullet of `R_unlock_pushes`).
- **The wake contract is the SPI's own**, not `CompletableFuture`'s — no async callbacks on
  arbitrary threads, no `cancel(mayInterruptIfRunning)`, no `obtrudeValue`.
- **The strategy sees every park**, which is the registry `session-destroy-ends-bare-parks.md`
  wants.

### What I'd change

- **A failure path.** `complete(R)` alone can't end a wait whose anchor died, nor one whose job
  failed: add `fail(Throwable)`, and let `park()` throw like `Future.get()` —
  `ExecutionException` around the cause, `InterruptedException` — which the API already unwraps
  into the `parkAndAwait` contract.
- **Drop `runUntilFirstPark`'s `Completable` parameter** — settled, see `Q_completable_param`.
- **Pass the `VaadinSession`, explicitly.** Implementors *do* know Vaadin, and must: loom queues
  continuations with `session.access` and needs its servlet's lock wrapper; background threads
  need `session.getLockInstance()`, and `VaadinSession.unlock()` for the pushing release. A
  Vaadin-free SPI would buy nothing. The session rather than the UI: it is the lock's scope, and a
  fiber follows its anchor to another UI on F5 (`R_preserve_migration`). Explicit rather than
  `VaadinSession.getCurrent()`, so the contract names it.

```java
package com.github.mvysny.blockingdialogs.spi;

public interface UIFiberRunnerSpi {
    /**
     * Runs fiber as a new UI fiber and returns once it first parks or ends, holding the lock again.
     * Called holding the lock of session, outside any fiber. Between the call and its return
     * nothing reaches the browser, and no other request runs. A fiber that parked continues
     * elsewhere once woken - on the strategy's own thread, never on the caller's. A fiber holds
     * the session lock everywhere except inside Completable.park(): a strategy holds it across
     * every other park - IO, a bare future.get() - and the "first park" is the first
     * Completable.park().
     */
    void runUntilFirstPark(VaadinSession session, Runnable fiber);

    /** A one-shot wake-up a fiber of session parks on. */
    <R> Completable<R> newCompletable(VaadinSession session);
}

public interface Completable<R> {
    /** Called holding the session lock; the first complete() or fail() wins. */
    void complete(R value);
    /** The wait is dead (a CancellationException, rethrown as-is by park()) or failed (any other cause). */
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

- **`Q_spi_name`** — the SPI type is the thing that *runs* UI fibers, not a fiber. Settled
  with Martin: **`UIFiberRunnerSpi`**. "Runner": it runs a `Runnable` in a highly controlled manner —
  and parking is part of running (it decides when a fiber runs *and* when it stops), so
  `newCompletable` belongs to it too. `Spi`: the API module depends on the SPI, so the type sits on
  every app's classpath and in its autocomplete — the suffix says "not for you" where the package
  can't. Implementations drop it: `LoomUIFiberRunner implements UIFiberRunnerSpi`, as in JCA. The
  cost: two words for one thing, "strategy" in prose and "runner" in code — rename the prose
  ("the loom runner", **One API, any runner**) when the modules land, as with "UI fiber".
  Earlier candidates:
  - **`UIFiberStrategy`** — every doc already says "strategy" (`D_pluggable_strategy`,
    "the loom strategy", **One API, any strategy**); `LoomUIFiberStrategy`, `ThreadUIFiberStrategy`.
  - **`UIFiberSpi`** — the JCA precedent: API class `Signature`, SPI class `SignatureSpi`, the API
    delegating, apps never touching the SPI — exactly this layering. JDK casing `Spi`, not `SPI`.
  - `UIFiberScheduler` — the role, but Loom readers hear "the virtual-thread scheduler", which we
    don't replace. `…Provider` is the other JDK `.spi` convention (`FileSystemProvider`).
  - **Not `…Executor`**: `Executor.execute` runs any task, some time, on some thread; this one
    demands the session lock, runs synchronously to the first park, and owns parking. The name
    invites `implements Executor` or `CompletableFuture.runAsync(…, it)`, both breaking the lock
    contract. Today's `BlockingExecutor` carries the same flaw, and the split retires it.

  And the app-facing primitives class: with the SPI lookup behind it, it holds only statics —
  `UIFibers.runLater(() -> …)` reads well and drops the `BlockingExecutor.get().` prefix.
- **`Q_module_names`** — settled with Martin: **`vaadin-uifiber-spi`**, the runners
  `vaadin-uifiber-<name>` (`vaadin-uifiber-loom`) — the fiber layer is not about dialogs, so only
  the API module keeps the `blocking-dialogs` name. The packages stay under the project's
  `com.github.mvysny.blockingdialogs`, as `….uifiber.<name>`: the repo is
  `mvysny/vaadin-blocking-dialogs`.
- **`Q_run_later_derived`** — two cracks in `runLater == access(runUntilPark)`:
  - loom on a *virtual* drainer (a virtual request thread, a background virtual thread's
    `ui.access`) can't mount, so its `runUntilFirstPark` must fall back to today's handoff and return
    before the park. Is "returns once it parks or ends, *or* at once where the strategy can't" an
    honest SPI contract, or does the API keep loom's refusal for `runUntilPark` and accept the
    fallback only under `runLater`?
  - background threads would release the holds from *inside* the unlock's drain. The raw release
    suits that too — `VaadinSession.unlock()` there would re-enter the drain — but the drain's own
    push then runs after the worker's first park, not before: check that is the push we want.
- **`Q_lock_between_parks`** — settled: the SPI promises the fiber holds the lock everywhere
  except inside `park()`. Loom breaks it at every IO unmount (`R_vt_unmount_releases_lock`); for
  now loom is accepted as "slightly broken". The fix is the rule of `Q_completable_param` — every
  `Completable.park()` releases, nothing else does — implemented as
  `loom-holds-the-lock-across-bare-unmounts.md` sketches it. The loom module's docs say so until then.
- **`Q_wrapping`** — the API wraps the body before handing it over, so the wrapper runs on the
  fiber's thread. Anything the wrapper must do on the *caller's* thread first (capturing the UI,
  `checkLockedUI`) happens in the API before `runUntilFirstPark`; confirm nothing in `runUIFiber` or
  `awaitAnchored` needs strategy internals.
- **`Q_completable_param`** — settled: dropped. Martin's reason for the parameter: it marks the one `Completable`
  whose `park()` releases the UI lock. Loom, seeing the virtual thread park on anything else (IO, a
  bare `future.get()`), keeps the lock and waits, carrying on until *that* `park()` is called — the
  fix for "slightly broken" (`Q_lock_between_parks`), and `loom-holds-the-lock-across-bare-unmounts.md`
  in SPI form: its `releasing` flag, set right before the strategy's own `get()`.
  - **The rule is right; the parameter is not needed for it.** The strategy implements
    `Completable.park()` itself, so it already knows a releasing park from a bare one: *every*
    `Completable.park()` releases, and nothing else does. Loom sets `releasing` inside its own
    `park()`; background threads release only there anyway.
  - **One designated instance would be too few.** A fiber parks many times on different
    completables — a confirm, then a second dialog, then a progress wait — each made by the API as
    the fiber goes. If only the passed-in one released, the second dialog's park would hold the
    lock, and its answering click could never get in: a deadlock.
  - **It also defines "first park"**: `runUntilFirstPark` returns at the first `Completable.park()`,
    never at IO — which fixes `RunUntilParkProbeTest.ioBeforeTheFirstDialog` for free.
  - **The price, on both strategies alike**: a bare park waiting for a UI answer (the migrator's
    `latch.await()`, `session-destroy-ends-bare-parks.md`) now freezes the session instead of
    working by accident under loom. Loud and consistent — **One API, any strategy** — but worth a
    WARN after N seconds with the fiber's stack (`Q_timeout_backstop` in the loom-holds idea).
  - Settled with Martin: the parameter is dropped, and the rule goes into the SPI javadoc —
    "every `Completable.park()` releases the lock, nothing else does".
- **`Q_settle_woken`** — the fibers a call wakes (`run-until-park-settles-woken-ui-fibers.md`:
  SB-Emulators' 40 modal-resume tests need them settled). Settled with Martin: **a wake-up is an
  access task** — the contract is Vaadin's event loop, not a scope. `complete()` / `fail()` queue
  the woken fiber on its session; at the next drain, before the lock is really released, it runs to
  its next `park()` or end — before any UIDL, before any other request. Loom does it already (the
  continuation is a `session.access` task the drainer mounts); background threads queue a raw
  handoff to the worker, as `runUntilFirstPark` does.
  - **Cascades settle in the same drain**: A's segment wakes B, whose task joins the queue
    `VaadinSession.unlock()` keeps draining until empty (`R_unlock_pushes`).
  - **No scope, so no `Q_scope_membership`**: every wake counts and none races — `complete()`
    holds the lock, and a background job's completion lands through `session.access` in a drain
    like any other.
  - **Exactly Swing's promise**: the modal's caller runs after the OK listener, before the next
    event — here after the listener, in the drain, before the response or the next request. It
    covers wakers that are no fiber too: `showAndAwait`'s plain OK listener.
  - **SB-Emulators keeps its drain** (`VaadinService.runPendingAccessTasks`), now sound under every
    runner. A test asserting straight after `_click` still needs a roundtrip, as `_fireConfirm` does.
  - Rejected: a second SPI call `runAllWokenUntilFirstPark()` — settled before the call returns,
    stronger than Swing, but every waker must remember to call it, a plain listener included; and
    the idea's scope-local queue, which needs the scope membership this avoids.
- **`Q_nested_run_later`** — `runLater` inside a fiber is `session.access(...)`, which runs at the
  next *drain*; a park releasing raw (`Condition.await()`) drains nothing. Settled with
  `Q_settle_woken`: **every real release of the lock drains first**. The caller's ultimate unlock
  does so by itself; every `Completable.park()` outside `runUntilFirstPark` drains before it
  releases, so a `runLater` or a woken fiber starts at the parent's park, as `runLater`'s javadoc
  promises — under background threads a handoff nested in the parent's park, before its `await()`.
  The first park inside `runUntilFirstPark` doesn't drain: the caller still holds the lock, and its
  own unlock drains.
- **`Q_epilogue_hook`** — should SB-Emulators reconcile in `ui.beforeClientResponse(...)` instead
  of after the listener? It then holds for every UIDL, pushes included, whatever the strategy.
  Does the API offer a "before every park" hook for it, or is Vaadin's own hook enough?
- **`Q_complete_threading`** — settled: the SPI's `complete()` / `fail()` are called holding the
  session lock. Swing tolerates closing a blocking dialog from a background thread — against the
  rules, but it works (a worker disposing its progress modal) — so under SB-Emulators `complete()`
  does get called lock-less. The API detects that (`!session.hasLock()`) and routes the call
  through `session.access` — SB-Emulators does the same today, per Martin. `session`, not `ui`: the
  fiber may have followed its anchor to a new UI, leaving the old one detached. The `Condition`
  needs the lock; loom wouldn't care; and the lock orders the wake-up: the fiber resumes only after
  the completing thread lets go.
- **`Q_future_cancel`** — settled: yes. When the anchor dies the API fails the `Completable` (the
  fiber wakes) *and* cancels the app's future, so a background job sees the wait is gone — today's
  promise that the anchor's detach cancels it (`D_anchored_wait`) stays. The first `complete()` /
  `fail()` wins, so an answer arriving in the detaching request isn't overturned.
- **Who calls `fail()`** — only the API, twice:
  1. the anchor watch, once the anchor stays detached: `fail(new CancellationException())`. The
     verdict waits for the detaching request's end, as today, so a `@PreserveOnRefresh` re-attach
     survives it; session destroy and tab close reach it too (`R_session_destroy_detaches`);
  2. `parkAndAwait`'s bridge: the app's future failed → `fail(cause)`; the app cancelled it →
     `fail(CancellationException)` — through `session.access` when lock-less.

  Never a dialog's Cancel button — a user's Cancel is an *answer*, `complete(CANCEL)`
  (`BlockingDialogs`' "Cancellation means the wait is dead"); never an interrupt, which is
  `park()` throwing `InterruptedException`; no strategy, as far as seen. `park()` then throws as
  `CompletableFuture.get()` does: a `CancellationException` as-is, any other cause in an
  `ExecutionException` — `CompletableFuture` treats `completeExceptionally(new
  CancellationException())` like `cancel()`, so loom gets it by wrapping one, and `fail(Throwable)`
  needs no `cancel()` beside it.
