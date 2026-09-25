# A UI-fiber SPI of its own, which no app ever calls

Today `BlockingExecutor` is both the SPI a strategy implements and, through `BlockingDialogs`' 1:1
static delegates, the API an app calls; `StrategySupport` is the strategy-neutral half every
strategy must remember to call. Split the audiences:

- **`vaadin-uifiber-spi`** — only `UIFiberRunnerSpi` and its `Completable`. Minimal surface,
  exhaustive javadoc: every precondition, every guarantee, every non-guarantee. No app calls it, ever.
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

**Where it stands** (2026-09-25): `vaadin-uifiber-spi` exists; its javadoc is the contract, and
`D_runner_owns_park`, `D_wake_is_access_task`, `D_in_fiber_flag` and `D_run_until_park`'s Hacky
why-not hold the reasons. Nothing uses it yet: the API and loom still speak `BlockingExecutor`.

- Settled, to build with the API:
  - `complete()` / `fail()` called lock-less get routed through `session.access` — `session`,
    not `ui`, since the fiber may have followed its anchor to a new UI. Swing tolerates closing a
    blocking dialog from a background thread (a worker disposing its progress modal), so under
    SB-Emulators it happens; SB-Emulators routes it the same way today.
  - Only the API calls `fail()`: the anchor watch, once the anchor stays detached, with a
    `CancellationException` — and it cancels the app's future too, keeping `D_anchored_wait`'s
    promise that a background job sees the wait is gone; `parkAndAwait`'s bridge, when the app's
    future fails (`fail(cause)`) or is cancelled (`fail(CancellationException)`). A user's Cancel
    is an answer, `complete(CANCEL)`; an interrupt is `park()` throwing `InterruptedException`.
  - The app-facing half of the one-thread promise — code after `confirm()` sees the thread-locals
    it saw before, MDC and transaction context included — goes into `BlockingDialogs`' docs.
  - `Q_wrapping` holds: `runUIFiber` and `awaitAnchored` need no runner internals, only the SPI's
    promises — the lock held with `hasLock()` true, one thread, `park()`'s semantics. The caller's
    thread does `checkLockedUI`, captures `ui` and `ui.getSession()`, and picks the inline branch;
    the rest runs in `wrap(ui, body)`, inside the runner's own prologue.
  - `parkAndAwait`'s bridge completes the `Completable` directly when the completing thread holds
    the lock, and only otherwise goes through `session.access`. An already-done future runs
    `whenComplete`'s callback at once on the fiber itself: through `session.access` it would leave
    `c` open, and the fiber would park for nothing — inside `runUntilFirstPark`, returning to the
    caller before the fiber got anywhere.
  - The wrapper guarantees `body` throws nothing: a throwing `ErrorHandler` is caught and logged,
    not left to the runner (under loom, the virtual thread's uncaught-exception handler, stderr).
  - The inline branch wraps differently: `inline-ui-fiber-keeps-the-rebound-ui.md`.
  - Around `c.park()` the API clears its in-fiber flag, and saves and clears the `CurrentInstance`s
    with it, restoring both on wake before the rebind: a parked fiber isn't running, and the
    scripted runner plays the next click on the fiber's thread (`D_in_fiber_flag`). Saved and
    restored, not merely cleared, so the fiber's own `CurrentInstance` entries survive the park.
- Settled, to build with the loom port: `Completable` wraps a `CompletableFuture`, `fail()` doing
  `completeExceptionally` — `get()` throws a `CancellationException` cause as-is, like `cancel()`,
  so no `cancel()` is needed. Loom breaks "only `park()` releases" at every IO unmount
  (`R_vt_unmount_releases_lock`) until `loom-holds-the-lock-across-bare-unmounts.md` lands; its
  docs say so meanwhile. The runner sees every park: the registry `session-destroy-ends-bare-parks.md` wants.
  The virtual thread is named after the session or a counter: the runner never sees the UI.
- Postponed by Martin: the probe of the raw-lock release for background threads.
- Next: `Q_run_later_derived`, `Q_eager_check`, `Q_fiber_condition`, `Q_epilogue_hook`,
  `Q_api_class_name`, and the prose
  rename "strategy" → "runner". Then port loom onto the SPI and rebuild the API on it.

Graduates when the API is rebuilt: the founding reasoning (below) to a `D_` that rewrites
`D_pluggable_strategy`'s cost paragraph and `D_spi_exactly_one`; the layering to the AGENTS.md
module map; the rest to the API's javadoc.

**The founding reasoning, for that `D_`.** The split itself, above. The name `UIFiberRunnerSpi`:
it *runs* UI fibers, parking included, so `newCompletable` belongs to it. `Spi`: the API depends
on the SPI, so the type sits in every app's autocomplete, and the suffix says "not for you" where
the package can't — the JCA precedent, `Signature` over `SignatureSpi`. Implementations drop it,
`LoomUIFiberRunner`. Why not `…Executor`: `Executor.execute` runs any task, some time, on some
thread; this one demands the session lock, runs synchronously to the first park and owns parking,
and the name invites `implements Executor` or `CompletableFuture.runAsync(…, it)`, both breaking
the lock contract — the flaw `BlockingExecutor` carries. The modules are `vaadin-uifiber-*`: the
fiber layer is not about dialogs.

## The strategies the SPI must fit

| | Loom | Background threads |
|---|---|---|
| Mechanism | the fiber is a virtual thread; each continuation runs as a session access task | the fiber is a worker platform thread; holds the lock, releases it around a park (`blocking-strategy-session-unlock.md`, Matthias's *Release Lock*) |
| Lock released on IO | **yes** — every unmount (`R_vt_unmount_releases_lock`), unless `loom-holds-the-lock-across-bare-unmounts.md` lands | no |
| Until next park, no UIDL meanwhile | yes: mount the first continuation on the caller, or queue it and let the drain before the real release run it (`R_unlock_pushes`) | not as sketched: the caller's release pushes; maybe with a raw-lock release, see below |
| Price | JDK 24+, `--add-opens`, reflection, the lock wrapper, platform request threads | a platform thread per open dialog; the handoff window of `D_input_exclusion` |

## What `runUntilPark` really promises: no UIDL until the park

The contract is `runUntilFirstPark`'s javadoc: until the return nothing reaches the browser and no
other request runs. Loom gives it by construction — the carrier *is* the caller and never releases
the lock.

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
4. every *later* park of the worker drains, pushes and then releases — nobody else is going to
   respond for it then.

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
drains first — `D_wake_is_access_task`), runs until its first park, and only then is the lock
really released: exactly `runLater`'s promise and `D_input_exclusion`. Loom does today's
`runLater` in effect this way already: its first continuation is queued as an access task. So the
lowest common denominator is not `runLater` with no guarantees, but **a handoff: "run this new
fiber until it parks or ends, then give me the lock back"** — each strategy its own way:

- **Loom** — mount the first continuation on the calling thread (today's `mountHere`).
- **Background threads** — release the raw lock holds (no push), start the worker, wait for its
  first park or end, re-take the holds — if the raw-release fix above holds.

`runUntilPark` inside a fiber is inline, and needs no strategy at all.

## The API on top

| API | on the SPI |
|---|---|
| `runLater(body)` | `session.access(() -> spi.runUntilFirstPark(session, wrap(ui, body)))` — see above |
| `runUntilPark(body)` | inside a fiber: `wrap(ui, body)` inline; else `spi.runUntilFirstPark(session, wrap(ui, body))` |
| `parkAndAwait(anchor, future)` | `c = spi.newCompletable(session)`; `future.whenComplete` → `session.access` → `c.complete` / `c.fail`; the anchor watch → `c.fail(new CancellationException())`; `unwrap(c::park)`, rebind the UI |
| `showAndAwait(confirmDialog)` | the dialog's listeners call `c.complete(outcome)` directly — they hold the lock; no future at all |
| `accessSynchronously` from a background thread | no fiber there: `access` + a plain `CompletableFuture.get()` on that thread |
| `isInUIFiber`, `checkInUIFiber`, `access` | API only (`D_in_fiber_flag`) |

`wrap(ui, body)` is today's `runUIFiber`: the `CurrentInstance`s, the in-fiber flag, the
`ErrorHandler`. The strategies never see it.

## Per strategy

- **Loom** — `park()` = the future's `get()`, which unmounts; `complete()` unparks, and the
  continuation queues as an access task the drainer mounts — the wake-up rule as it stands.
  `runUntilFirstPark` is today's `mountHere` start.
- **Background threads** — `Condition`s of the session's lock: `park()` drains
  (`VaadinService.runPendingAccessTasks`), pushes, then `await()`s; `complete()` queues
  `session.access(() -> hand the lock raw to the worker, wait for its next park or end)`.
  `runUntilFirstPark` = start the worker, `handoff.await()`, the worker `signal()`ing it at its
  first park. A fiber that ends without parking must also release raw and signal, or its final
  `VaadinSession.unlock()` pushes the half-done state before the caller's response.
- **Scripted (tests)** — `park()` plays the next scripted user action, which completes it, and
  returns; `runUntilFirstPark` runs the fiber inline to its end.

## The epilogue before every UIDL

Found while rejecting Hacky, and worth keeping on its own: SB-Emulators reconciles Swing state onto
Vaadin in an epilogue after the listener. In `ui.beforeClientResponse(...)` instead, it would run
before every UIDL, a push's included (`R_unlock_pushes`), under any strategy — and that need would
stop depending on `runUntilPark` (`Q_epilogue_hook`).

## Open questions

- **`Q_run_later_derived`** — two cracks in `runLater == access(runUntilPark)`:
  - loom on a *virtual* drainer (a virtual request thread, a background virtual thread's
    `ui.access`) can't mount, so its `runUntilFirstPark` must fall back to today's handoff and return
    before the park. Is "returns once it parks or ends, *or* at once where the strategy can't" an
    honest SPI contract, or does the API keep loom's refusal for `runUntilPark` and accept the
    fallback only under `runLater`? The same crack hits a woken fiber's access task drained there.
  - background threads would release the holds from *inside* the unlock's drain. The raw release
    suits that too — `VaadinSession.unlock()` there would re-enter the drain — but the drain's own
    push then runs after the worker's first park, not before: check that is the push we want.
- **`Q_eager_check`** — `runUntilFirstPark` throws `IllegalStateException` where it can't run a
  fiber: loom's unwrapped session lock, a virtual thread. Today `runLater` calls `start()` on the
  caller, so a misconfigured app fails at the call site; with `runLater = session.access(() ->
  runUntilFirstPark(…))` the throw happens in the drain and lands in the `ErrorHandler`. Accept it
  — a misconfiguration is loud on first use either way, the message names the fix — or give the
  SPI a check the API calls on the caller's thread? Leaning: accept, and keep the SPI minimal.
- **`Q_fiber_condition`** — should a fiber get a working `Condition`? Loom's pretend hold throws
  on `newCondition()`; a background-thread runner's native one would `await()` raw — no drain, no
  push — breaking `D_wake_is_access_task`. Nested blocking dialogs need none: each fiber parks on
  its own `Completable`, a Swing secondary loop inside a secondary loop. Should a use case appear
  (SB-Emulators emulating `SecondaryLoop`?), the API can build one on `Completable` — `await()` a
  new `Completable`'s park, `signal()` its `complete()` — under every runner, the SPI untouched.
  Until then the SPI leaves it undefined.
- **`Q_epilogue_hook`** — should SB-Emulators reconcile in `ui.beforeClientResponse(...)` instead
  of after the listener? It then holds for every UIDL, pushes included, whatever the strategy.
  Does the API offer a "before every park" hook for it, or is Vaadin's own hook enough?
- **`Q_api_class_name`** — the app-facing primitives class: with the SPI lookup behind it, it
  holds only statics — `UIFibers.runLater(() -> …)` reads well and drops the
  `BlockingExecutor.get().` prefix.
