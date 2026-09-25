# A `Condition` a UI fiber can wait on

Deferred by Martin until a use case appears. Today the SPI leaves `Condition`s on the session lock
undefined inside a fiber (`UIFiberRunnerSpi`'s "The lock": only nested `lock()` / `unlock()` pairs
are defined).

**Why not the runner's native one.** Loom's pretend hold throws on `newCondition()`: `await()`
would release the lock on the carrier's behalf. A background-thread runner's native `Condition`
would `await()` raw — no drain, no push — a second releasing park beside `Completable.park()`,
breaking `D_runner_owns_park` and `D_wake_is_access_task`.

**Not needed for nested blocking dialogs.** Each fiber parks on its own `Completable`: fiber A
parks on dialog 1, dialog 1's button starts fiber B through `runLater`, B parks on dialog 2 — a
Swing secondary loop inside a secondary loop.

**If a use case appears** (SB-Emulators emulating `SecondaryLoop`?), the API builds one on
`Completable`, under every runner, the SPI untouched: `await()` makes a new `Completable` and
parks on it, `signal()` completes it.

## Open questions

- `Q_condition_use_case`: does SB-Emulators, or anything else, actually need it?
- `Q_condition_shape`: a real `java.util.concurrent.locks.Condition` — `awaitNanos`, `awaitUntil`,
  spurious wake-ups, `signalAll` over many parked fibers — or a smaller one-shot type?
