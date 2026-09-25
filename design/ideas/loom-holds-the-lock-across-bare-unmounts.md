# Loom keeps the session lock across every unmount but `parkAndAwait`'s

`R_vt_unmount_releases_lock`: under loom a UI fiber drops the session lock at *any* unmount — a
socket read, `Thread.sleep`, a contended lock — not only at `parkAndAwait`. That breaks three
things:

- **`D_input_exclusion`**: IO before the first dialog lets a double click start the action twice
  (`IoUnmountProbeTest.aSocketReadBeforeTheFirstDialogLetsADoubleClickIn`).
- **One API, any runner**: `UIFiberRunnerSpi`'s "The lock" already owes it — every `park()`
  releases the lock, nothing else does; IO, a bare `Future.get()` or `Thread.sleep()` keeps it, and
  the "first park" is never an IO wait. Loom breaks that, and `LoomUIFiberRunner`'s `@apiNote` says
  so meanwhile. So the same UI fiber is atomic on one runner and interleaved on the other.
- **Ordinary Vaadin expectations**: a listener doing a JDBC call is atomic against the
  session's other requests. Wrapped in `runLater`, it silently no longer is. SB-Emulators feels
  this hardest: every Swing listener there runs as a UI fiber, and a Swing app doing a DB query on the
  EDT is the norm, where the EDT never interleaves listeners.

So this is no new rule for loom, only loom meeting the SPI's; a platform-thread runner meets it by
construction. Afterwards the runners differ only inside `park()`, where loom holds no thread — IO
costs the same on all of them, as on Swing's EDT.

It also fixes `runUntilPark` returning at the first IO rather than the first park
(`IoUnmountProbeTest.runUntilParkReturnsAtASocketReadBeforeTheFirstPark`): `Thread.start()` returns at any unmount.

It also subsumes most of `session-destroy-ends-bare-parks.md`. A bare `future.get()` would then
hold the lock, freezing the UI at once — loud, as it already is under session-unlock — instead of
leaking silently at session end.

Graduates as a short `D_` (why loom pays the carrier thread for bare unmounts), linking the SPI's
"The lock" rather than restating it; the carrier flow in the loom classes' doc comments, whose class
doc gains "IO, `sleep` and contended locks hold the carrier and the lock, as on a platform thread;
only `park()` frees both" and loses the `@apiNote`; and the probe turned into regression tests.

## Mechanism sketch

Only an unmount inside `parkAndAwait` releases the lock. For any other unmount, the carrier stays
inside its access task, holding the lock, and waits for that UI fiber's next continuation.

- The state lives on `SessionCarrier`, already one per UI fiber (`runUntilFirstPark` news one up):
  `releasing` (set on the UI fiber's own thread right before `future.get()` in
  `LoomUIFiberRunner.WakeUp.park()`, cleared on resume) and a one-slot hand-off queue, which the
  watchdog owns (`Q_timeout_backstop`).
- `SessionCarrier.mount`: run the continuation; when it returns with the thread still alive and
  `!releasing`, it unmounted for something else. So `take()` the next continuation from the
  hand-off queue and run it inline, looping, still inside the same access task. The run-take loop lives in
  `mount`, so the first segment (inside `Thread.start()`) and every later one get it; on a virtual
  drainer it goes inside the task submitted to `Handoff.POOL`.
- `SessionCarrier.execute`: the first submit (the start) and every submit while `releasing` go to
  `session.access` as today; any other submit goes to the hand-off queue. The ordering is safe:
  `releasing` is written by the UI fiber before it parks, and the resubmit happens after.
- The resubmit can arrive on the carrier itself, nested inside `continuation.run()`: the JDK finds
  the unpark permit while finishing the yield and submits at once. So the hand-off `offer` never
  blocks; the carrier `take()`s it right after `run()` returns. For a park the nested submit is a
  `session.access` on a thread holding the lock, which only queues.
- The carrier is the request thread, so it blocks for the duration of the IO — exactly what a
  plain Vaadin listener doing that IO costs. `Thread.sleep(200)` in a UI fiber becomes a 200 ms
  frozen request, as it is on a platform thread.

## Settled

- `Q_carrier_blocks`: blocking the carrier is right. Vaadin's lock is thread-owned and the
  response needs it, so *some* thread waits holding it for the IO anyway; moving ownership to the
  fiber would just block the request thread instead. Loom's "parks without holding a thread" is
  for waiting on a human (`park()`, seconds to minutes), not on a database (milliseconds).
- `Q_inherited_threads`: `LoomUtils.newVirtualThread` hands the carrier only the UI fiber's own
  continuation; an inherited virtual thread's go to `InheritedThreadCarriers.POOL`.
- `Q_nested_ui_fibers`: each UI fiber has its own `SessionCarrier`, so its own queue, and
  `runUntilFirstPark` refuses a UI virtual thread, so no UI fiber mounts another on its carrier.
  Still worth a test: `accessSynchronously` from a UI fiber into a new one.
- `Q_timeout_backstop`: a bare `future.get()` waiting for a click now deadlocks the session, as
  under session-unlock; it stays a deadlock, and a watchdog reports it, shipping with the fix.
  Thread dumps alone don't cut it: the JVM's detector finds no Java-level cycle (the carrier waits
  on an ownerless queue, the UI fiber on a socket or a future, the row lock lives in the DB), and
  the culprit line sits on the UI fiber's virtual thread, which `jstack` omits — it takes
  `jcmd Thread.dump_to_file`.
  - A class of its own, `LockHoldWatchdog` (name open; `Handoff` is taken by the virtual-drainer
    pool), owning the one-slot hand-off queue: `SessionCarrier.execute` `offer`s to it, `mount`
    calls its `take()`, which owns the loop and the logging.
  - `take()` `poll`s with a timeout; on each timeout it WARNs "UI fiber X has held the session lock
    for N s outside `park()`" with the UI fiber's own stack and a hint (a bare `Future.get()`, a
    DB lock, a long query?), backing off (10 s, 30 s, 90 s, …), and keeps waiting holding the lock.
    Once the continuation arrives after a WARN it logs "resumed after N s", telling a slow query
    from a deadlock.
  - `blockingdialogs.uifiber.loom.lockHoldWarnSeconds`, default 10, 0 disables. SLF4J, as
    `UIFibers`; the loom module gains `implementation(libs.slf4j.api)`.
  - A slow query WARNs too, rightly: it freezes the whole session, as it would the EDT.
  - Blind to a carrier stuck *inside* `continuation.run()` — file IO, JDK 21 pinning — but a plain
    thread dump shows those, the carrier's own stack being the culprit.
  - Loom only: session-unlock's thread blocks directly, with no loop to hook. A diagnostic changes
    no behaviour, so it stays out of the SPI.
  - `[unverified]` `Thread.getStackTrace()` of an unmounted virtual thread on our own scheduler
    returns the suspended continuation's stack; a test confirms it, then `research.md` gets it.

## Open questions

- `Q_parked_holder_deadlock`: holding the lock across IO lets a UI fiber wait on what a *parked*
  UI fiber holds. Fiber B opens a transaction, updates a row, parks in `confirm("Commit?")`; fiber
  A, in another tab of the same session, updates the same row — its JDBC read waits on the row
  lock holding the session lock, so B's answer never arrives, until the DB's lock timeout. Within
  one tab the modal blocks A's click; across tabs it doesn't (the lock is per session, dialogs per
  UI). Today's loom survives it; session-unlock deadlocks alike, so this is parity, not a
  regression — but a transaction across a dialog is Swing style, so SB-Emulators apps will hit it.
  Swing deadlocks the same way: B's `JDialog` is `DOCUMENT_MODAL` to window 1, A's click comes
  from window 2, A's `UPDATE` runs on the EDT nested inside B's `setVisible(true)` and blocks it,
  so the dialog can't be answered. Three conditions, same in both: a dialog modal to its own window
  only, a connection per listener (a pool), and A blocking the one thing that runs B's answer (the
  EDT; the session lock). Swing meets them less often — `JOptionPane` is `APPLICATION_MODAL`, and
  classic Swing apps share one `Connection`, so A joins B's transaction — though a `Timer` or an
  `invokeLater` fires inside any modal loop (in Vaadin: a background `ui.access()` starting a UI
  fiber). Vaadin's per-UI modal is the weaker `DOCUMENT_MODAL`, so it is likelier here, not
  different in kind. The watchdog (`Q_timeout_backstop`) at least names A's stuck line.
- `Q_jdk21`: with `-Dblockingdialogs.uifiber.loom.allowPinningJdk=true`, `synchronized` pins instead of
  unmounting. That already holds the lock, so this changes nothing there.
- Afterwards an app that wants the UI live during long IO has no accidental way left; it needs a
  deliberate one — a SwingWorker-like `awaitInBackground(task)` that parks the UI fiber on an
  executor, ideally behind a modal progress dialog so `D_input_exclusion` holds. Its own idea if
  pursued.

## Tests on graduation

The probe's assertions flipped (clicks stay 0 across the read, one UI fiber per double click,
`runUntilPark` returns with the dialog open), plus: the resubmit arriving on the carrier itself;
IO after a dialog answer, carried by the answering request; IO on the virtual-drainer
(`Handoff`) path; the watchdog WARNing with the UI fiber's stack past its threshold, then
"resumed", and silent at 0.
