# Loom keeps the session lock across every unmount but `parkAndAwait`'s

`R_vt_unmount_releases_lock`: under loom a block drops the session lock at *any* unmount — a
socket read, `Thread.sleep`, a contended lock — not only at `parkAndAwait`. That breaks three
things:

- **`D_input_exclusion`**: IO before the first dialog lets a double click start the action twice
  (`IoUnmountProbeTest.aSocketReadBeforeTheFirstDialogLetsADoubleClickIn`).
- **One API, any strategy**: under session-unlock, a block holds the lock except inside the park
  (`StrategySupport`'s class doc says so: "it runs holding the session lock except inside
  `Park#park()`"). Loom breaks that owed invariant. So the same block is atomic on one strategy
  and interleaved on the other.
- **Ordinary Vaadin expectations**: a listener doing a JDBC call is atomic against the
  session's other requests. Wrapped in `runLater`, it silently no longer is. SB-Emulators feels
  this hardest: every Swing listener there runs as a block, and a Swing app doing a DB query on the
  EDT is the norm, where the EDT never interleaves listeners.

It also subsumes most of `session-destroy-ends-bare-parks.md`. A bare `future.get()` would then
hold the lock, freezing the UI at once — loud, as it already is under session-unlock — instead of
leaking silently at session end.

Graduates as a `D_` (why loom holds the lock across bare unmounts), the carrier flow in the
loom classes' doc comments, and the probe turned into regression tests.

## Mechanism sketch

Only an unmount inside `parkAndAwait` releases the lock. For any other unmount, the carrier stays
inside its access task, holding the lock, and waits for that block's next continuation.

- Per block, a small state object: `releasing` (set on the block's own thread right before
  `future.get()` in `LoomBlockingExecutor.parkAndAwait`'s `Park`, cleared on resume) and a
  one-slot hand-off queue.
- `SessionCarrier.mount`: run the continuation; when it returns with the thread still alive and
  `!releasing`, it unmounted for something else. So `take()` the next continuation from the
  hand-off queue and run it inline, looping, still inside the same access task.
- `SessionCarrier.execute`: the first submit (the start) and every submit while `releasing` go to
  `session.access` as today; any other submit goes to the hand-off queue. The ordering is safe:
  `releasing` is written by the block before it parks, and the resubmit happens after.
- The carrier is the request thread, so it blocks for the duration of the IO — exactly what a
  plain Vaadin listener doing that IO costs. `Thread.sleep(200)` in a block becomes a 200 ms
  frozen request, as it is on a platform thread.

## Open questions

- `Q_carrier_blocks`: is blocking the carrier (the request thread) acceptable? It is the
  platform-thread cost, and session-unlock pays it too. But loom's selling point is "parks without
  holding a thread", so say plainly that this applies to `parkAndAwait` only.
- `Q_inherited_threads`: a virtual thread a block starts inherits the scheduler, and
  `LoomUtils`' inherited-thread carriers route it away from `SessionCarrier`, so it is unaffected.
  Confirm the hand-off routing only ever sees the block's own continuation.
- `Q_nested_blocks`: `runLater` inside a block, and `accessSynchronously` from a block into a new
  block — each has its own state object. Does any path unmount block A while block B's continuation
  waits on the same carrier? A deadlock here would be a nested take() on a queue nobody feeds.
- `Q_timeout_backstop`: a bare `future.get()` waiting for a click now deadlocks the session, as
  under session-unlock. Keep it a deadlock (loud, and `D_pluggable_strategy` already forbids it),
  or WARN after N seconds with the block's stack while holding on?
- `Q_jdk21`: with `-Dblockingdialogs.loom.allowPinningJdk=true`, `synchronized` pins instead of
  unmounting. That already holds the lock, so this changes nothing there.
