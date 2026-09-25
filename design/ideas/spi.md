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

**Scope: what SB-Emulators needs today**, read from its code on 2026-09-25 — loom only, a
`runUntilPark` (its `callSwing` drains after submitting), inline nesting, several fibers parked at
once, a modal closed from a background thread, a parked modal following F5, fiber exceptions to the
`ErrorHandler`. Everything else is deferred:

- the background-thread runner — `blocking-strategy-session-unlock.md`, its "On the SPI";
- a scripted test runner — `scripted-test-runner.md`;
- the epilogue before every UIDL — `epilogue-before-every-uidl.md`;
- a `Condition` a fiber can wait on — `ui-fiber-condition.md`.

**Next:** `Q_run_later_derived`, `Q_api_class_name`, and the prose rename "strategy" → "runner".
Then port loom onto the SPI and rebuild the API on it.

## Settled, to build with the API

- `complete()` / `fail()` called lock-less get routed through `session.access` — `session`, not
  `ui`, since the fiber may have followed its anchor to a new UI. Swing tolerates closing a blocking
  dialog from a background thread (a worker disposing its progress modal), so under SB-Emulators it
  happens.
- Only the API calls `fail()`: the anchor watch, once the anchor stays detached, with a
  `CancellationException` — and it cancels the app's future too, keeping `D_anchored_wait`'s promise
  that a background job sees the wait is gone; `parkAndAwait`'s bridge, when the app's future fails
  (`fail(cause)`) or is cancelled (`fail(CancellationException)`). A user's Cancel is an answer,
  `complete(CANCEL)`; an interrupt is `park()` throwing `InterruptedException`.
- The app-facing half of the one-thread promise — code after `confirm()` sees the thread-locals it
  saw before — goes into `BlockingDialogs`' docs.
- `Q_wrapping` holds: `runUIFiber` and `awaitAnchored` need no runner internals, only the SPI's
  promises — the lock held with `hasLock()` true, one thread, `park()`'s semantics. The caller's
  thread does `checkLockedUI`, captures `ui` and `ui.getSession()`, and picks the inline branch;
  the rest runs in `wrap(ui, body)`, inside the runner's own prologue.
- `parkAndAwait`'s bridge completes the `Completable` directly when the completing thread holds the
  lock, and only otherwise goes through `session.access`. An already-done future runs
  `whenComplete`'s callback at once on the fiber itself: through `session.access` it would leave
  `c` open, and the fiber would park for nothing — inside `runUntilFirstPark`, returning to the
  caller before the fiber got anywhere.
- The wrapper guarantees `body` throws nothing: a throwing `ErrorHandler` is caught and logged, not
  left to the runner (under loom, the virtual thread's uncaught-exception handler, stderr).
- The inline branch wraps differently: `inline-ui-fiber-keeps-the-rebound-ui.md`.
- `Q_eager_check`, settled with Martin — the SPI stays minimal. The API checks what is
  runner-neutral eagerly on the caller's thread, as today: `checkLockedUI` (the lock, a current UI)
  and the in-fiber branch. A runner's setup error thrown by `runUntilFirstPark` inside `runLater`'s
  drain lands in the `ErrorHandler`: accepted, as loud on first use and naming the fix. A runner
  wanting fail-fast checks its setup at its own earliest point.

## Settled, to build with the loom port

- `park()` is a `CompletableFuture`'s `get()`, which unmounts; `complete()` unparks, and the
  continuation queues as an access task the drainer mounts — the wake-up rule as it stands.
  `runUntilFirstPark` is today's `mountHere` start. `fail()` does `completeExceptionally`: `get()`
  throws a `CancellationException` cause as-is, like `cancel()`, so no `cancel()` is needed.
- Loom breaks "only `park()` releases" at every IO unmount (`R_vt_unmount_releases_lock`) until
  `loom-holds-the-lock-across-bare-unmounts.md` lands; its docs say so meanwhile.
- The runner sees every park: the registry `session-destroy-ends-bare-parks.md` wants.
- The virtual thread is named after the session or a counter: the runner never sees the UI.
- Fail-fast on an unwrapped session lock (`Q_eager_check`): a `VaadinServiceInitListener` in the
  loom jar, found through `META-INF/services`, checks `session.getLockInstance()` at session init —
  the first session fails, before any `runLater`. `D_loom_servlet` counts shipping one as a cost,
  but for the anchor verdict; for a check that only throws, weigh it again.

## The founding reasoning, for its `D_`

Graduates when the API is rebuilt: to a `D_` that rewrites `D_pluggable_strategy`'s cost paragraph
and `D_spi_exactly_one`; the layering to the AGENTS.md module map; the rest above to the API's
javadoc.

The split itself, above. The name `UIFiberRunnerSpi`: it *runs* UI fibers, parking included, so
`newCompletable` belongs to it. `Spi`: the API depends on the SPI, so the type sits in every app's
autocomplete, and the suffix says "not for you" where the package can't — the JCA precedent,
`Signature` over `SignatureSpi`. Implementations drop it, `LoomUIFiberRunner`. Why not
`…Executor`: `Executor.execute` runs any task, some time, on some thread; this one demands the
session lock, runs synchronously to the first park and owns parking, and the name invites
`implements Executor` or `CompletableFuture.runAsync(…, it)`, both breaking the lock contract — the
flaw `BlockingExecutor` carries. The modules are `vaadin-uifiber-*`: the fiber layer is not about
dialogs.

## `runLater` falls out of `runUntilPark`

The access tasks pending at the ultimate unlock run *before* the lock is really released
(`R_unlock_pushes`, the last-but-one bullet). So

```java
runLater(body) == session.access(() -> runUntilPark(body))
```

— the new fiber starts after the listener returns (or, inside a fiber, at its park, since that
drains first — `D_wake_is_access_task`), runs until its first park, and only then is the lock
really released: exactly `runLater`'s promise and `D_input_exclusion`. Loom does today's `runLater`
in effect this way already: its first continuation is queued as an access task. So the lowest
common denominator is not `runLater` with no guarantees, but **a handoff: "run this new fiber until
it parks or ends, then give me the lock back"** — loom mounts the first continuation on the calling
thread (today's `mountHere`). `runUntilPark` inside a fiber is inline, and needs no runner at all.

## The API on top

| API | on the SPI |
|---|---|
| `runLater(body)` | `session.access(() -> spi.runUntilFirstPark(session, wrap(ui, body)))` — see above |
| `runUntilPark(body)` | inside a fiber: `wrap(ui, body)` inline; else `spi.runUntilFirstPark(session, wrap(ui, body))` |
| `parkAndAwait(anchor, future)` | `c = spi.newCompletable(session)`; `future.whenComplete` → `c.complete` / `c.fail`; the anchor watch → `c.fail(new CancellationException())`; `unwrap(c::park)`, rebind the UI |
| `showAndAwait(confirmDialog)` | the dialog's listeners call `c.complete(outcome)` directly — they hold the lock; no future at all |
| `accessSynchronously` from a background thread | no fiber there: `access` + a plain `CompletableFuture.get()` on that thread |
| `isInUIFiber`, `checkInUIFiber`, `access` | API only (`D_in_fiber_flag`) |

`wrap(ui, body)` is today's `runUIFiber`: the `CurrentInstance`s, the in-fiber flag, the
`ErrorHandler`. The runners never see it.

## Open questions

- **`Q_run_later_derived`** — loom on a *virtual* drainer can't mount, so `runUntilFirstPark` throws
  there ("a thread the runner can't carry"), and `runLater == access(runUntilPark)` would strand a
  fiber that today's loom hands to a platform thread. The same goes for a woken fiber's access task
  drained there. Reachable in SB-Emulators though untested, read from its code and JDK 25's
  sources, not run: its request threads are platform, but a foreign virtual thread waking a parked
  fiber while the lock is free drains the queue itself, and on Linux JDK 25's pollers are virtual
  (`Poller.Mode.VTHREAD_POLLERS`), so a fiber resumed after IO may be drained by one. SB-Emulators
  throws there and strands the fiber; our loom hands off. Does the SPI allow "returns at once where
  it can't carry the caller, the fiber handed off" for a drained start and a wake-up, keeping the
  throw only for the API's `runUntilPark`? Or does the API route those two through a path that
  never calls `runUntilFirstPark` on a virtual drainer?
- **`Q_api_class_name`** — the app-facing primitives class: with the SPI lookup behind it, it holds
  only statics — `UIFibers.runLater(() -> …)` reads well and drops the `BlockingExecutor.get().`
  prefix.
