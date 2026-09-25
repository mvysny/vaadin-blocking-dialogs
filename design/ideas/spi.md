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
- the epilogue before every UIDL — `epilogue-before-every-uidl.md`;
- a `Condition` a fiber can wait on — `ui-fiber-condition.md`.

**Next:** the prose rename "strategy" → "runner". Then
port loom onto the SPI and rebuild the API on it.

## Settled, to build with the API

- The primitives class is `UIFibers`, all statics over the SPI lookup — `UIFibers.runLater(() ->
  …)`, no `BlockingExecutor.get().` prefix (was `Q_api_class_name`, settled with Martin).
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
- The API's own tests run on loom, a test dependency once loom depends on the SPI only;
  `ScriptedBlockingExecutor` retires with `BlockingExecutor` (was `Q_scripted_worth`, settled with
  Martin). Why not port it to the SPI: a scripted suite never exercises the real runner, parks
  included — the classic hazard — and SB-Emulators already tests on loom under Karibu, a modal
  driven by a click from a second listener. What it gave, no threads and so no race between a
  click and an assertion, matters only to the background-thread runner, deferred with it
  (`Q_karibu_determinism`). The API still clears its in-fiber flag around `park()`: the SPI lets a
  runner run another fiber on the thread meanwhile.
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

- **`Q_run_later_derived`** — settled with Martin, probed and built into today's loom: a
  continuation reaching a *virtual* drainer runs on a platform handoff thread while the drainer
  waits, holding the session lock (`LoomBlockingExecutor.SessionCarrier.mount`). So the segment
  runs before the drainer's push and before any queued request, as on a platform drainer;
  `runUntilPark` no longer refuses a virtual caller; `runLater == access(runUntilPark)` holds on
  every drainer, and `D_wake_is_access_task` without exception — the SPI untouched.
  `VirtualDrainerProbeTest` shows it for a fiber start, a wake-up, a cascade and `runUntilPark`
  (JBR 25.0.4); today's loom failed all four. Why the old handoff, `session.accessSynchronously`
  on a platform thread, was wrong: it took the lock once the drainer let go, so the drainer's push
  and a queued request got in first. The risk isn't new: a drainer holding an app lock the segment
  needs deadlocks, as a platform drainer running the continuation inline does.

  Why it matters to SB-Emulators, read from its code and JDK 25's sources, not run: its request
  threads are platform, but a foreign virtual thread waking a parked fiber while the lock is free
  drains the queue itself, and on Linux JDK 25's pollers are virtual
  (`Poller.Mode.VTHREAD_POLLERS`), so a fiber resumed after IO may be drained by one. SB-Emulators
  throws there and strands the fiber.

  Left open:
  - `Q_virtual_request_threads` — with this, loom may run on virtual HTTP request threads: the
    request thread waits while a platform thread carries each segment. Karibu has no request
    threads, so check it in a real container (the testapp with `useVirtualThreadsIfAvailable(true)`)
    before lifting the AGENTS.md invariant and the README requirement.
  - `Q_poller_submit` — a virtual poller that unparks a fiber now waits out a whole UI segment,
    holding up IO for other sockets. Route a submit made from a virtual thread that isn't one of our
    fibers through a platform thread's `session.access`, so a poller never takes the session lock?
    Our own fibers submit directly, their pretend lock only queuing. Unmeasured.
