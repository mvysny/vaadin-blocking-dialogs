# SB-Emulators on this library: what it needs, graded

SwingBridge Emulators (SB-Emulators) dropped its vendored `:loom` for `vaadin-blocking-dialogs` +
`vaadin-uifiber-loom` 0.1-SNAPSHOT from `~/.m2` (2026-09-25, branch `blocking-dialogs-on-uifiber`).
Re-run on the jars with `D_loom_holds_the_lock` and `WaitDiedException` (commit `49ab6a6`), Karibu
only, no real browser yet: the whole SB-Emulators reactor is green — 2189 `:emulators` tests, 22
printing, 7 Spring, 92 Sampler, both add-ons — with a 120 s per-test timeout and not one
lock-hold WARN in the log. One test had parked bare on `future.get()` and hung its Karibu thread,
exactly as `D_loom_holds_the_lock` says it now must; it parks through `parkAndAwait` now.

The port:

- `EHelper.callSwing` runs every Swing listener as `UIFibers.runUntilPark(body)`. It then drains
  the access queue, so the modal fiber a click woke settles before the next event, as in Swing
  (`D_wake_is_access_task` makes that work), and then runs its own epilogue. A nested call checks
  `isInUIFiber()` and runs inline, propagating exceptions.
- A modal `Dialog.show()` parks on `parkAndAwait(dialogPeer, closedFuture)`. The three browser
  round-trips (preferences, clipboard, client details) park on `parkAndAwait(ui, future)`. All four
  used to be bare `latch.await()` / `future.get()`.

Grades: **now** = blocks merging the switch; **soon** = right after; **later** = worth doing, no
pressure; **not needed** = SB-Emulators has no use for it; graduate or keep on its own merits.

## Now

- **Publish to Maven Central.** An SB-Emulators release can't depend on a SNAPSHOT that lives only in
  `~/.m2`. `Q_coordinates`: does it stay `com.github.mvysny.vaadin-blocking-dialogs`, or move to
  Vaadin coordinates, given that a Vaadin product depends on it?

## Soon

- **A Java monitor held across a park, contended by a second UI fiber of the session.** Fiber A
  enters `synchronized (m)` and parks: fine, the park releases the session lock. Fiber B of the same
  session then blocks entering `m`, and under `D_loom_holds_the_lock` keeps the session lock while it
  waits. A's wake-up is a request that needs that lock, and A holds `m`: a two-lock deadlock, for
  good. Swing never hits it, since A and B are the same EDT (B runs nested in A's modal loop) and a
  monitor is reentrant; the old `:loom` never hit it either, since B's unmount released the lock.
  `D_loom_holds_the_lock` names the DB-row version, which "waits out the DB's lock timeout", but a
  Java monitor has no timeout, and the watchdog WARNs forever.
  - SB-Emulators had two cases of its own, both fixed on its side by not holding the monitor across
    the wait: `WebClipboard.getContents` / `setContents` were `synchronized` (two quick pastes during
    a clipboard-permission prompt froze the session), and the first preferences load parked inside
    `AbstractPreferences`' node lock. Left: migrated code showing a modal from inside `synchronized`
    while a `Timer` or the dialog's own listener needs the same monitor. Rare; the testapps have none.
  - `Q_monitor_held_by_parked_fiber`: say so in `D_loom_holds_the_lock`, as the third accepted
    deadlock, and in the watchdog's WARN ("blocked on a monitor another UI fiber of this session
    holds across a park?")? Or detect it — the watchdog sees the fiber `BLOCKED`, not waiting on IO,
    and could release the lock for a monitor wait only, as the old behaviour did. That trades the
    deadlock for interleaving against a *background* thread holding the monitor, where Swing would
    block the EDT. SB-Emulators prefers the WARN wording: faithful, and loud.

## Later

- **`session-unlock-runner.md`.** For SB-Emulators it would drop the JDK 24 floor, `--add-opens`,
  the lock wrapper, and three of its deployment-refusal throws (the JDK check, the unwrapped lock,
  virtual request threads). It replaces SB-Emulators' own
  `ideas/blocking-strategy-manual-session-unlock.md`, which should move here or be deleted on that
  side. The obstacle is still `Q_karibu_determinism`: SB-Emulators' 2182 tests all lean on Karibu
  draining on the test thread.
- **`loom-on-virtual-request-threads.md`.** If `Q_virtual_request_threads` passes, SB-Emulators can
  drop its throw on virtual request threads. `Q_poller_submit` matters to it even without that, on
  JDK 25 Linux.

## Not needed

- **`ui-fiber-condition.md`.** `Q_condition_use_case`: no. SB-Emulators' `EventQueue.createSecondaryLoop`
  is a WARN stub, and nested modals each park on their own wait, as that idea says.
- **`testapp.md`.** SB-Emulators' Sampler is its testbed.
- **A published Karibu fixture (`MockVirtualThreadAwareServlet`).** SB-Emulators keeps its own copy,
  and `D_loom_servlet` explains why none is published.
- **The `allowPinningJdk` opt-in.** SB-Emulators refuses Java 21-23 itself at servlet init and never
  sets it, so `D_loom_jdk_gate` stands as is.

## SB-Emulators-side, no library work

Recorded so they aren't mistaken for library asks:

- A dead modal's `WaitDiedException` now escapes `JOptionPane.show*` into migrated code, where a
  `catch (Exception e)` swallows it and the listener runs on after its session died. SB-Emulators'
  convention for "a blocking call nobody can answer" is its own `Error` subtype, which a
  `catch (Exception)` cannot swallow. Convert `WaitDiedException` only, at the `Dialog` seam, and
  keep it out of the ErrorHandler during teardown.
- A migrator's bare `future.get()` / `latch.await()` on the EDT now freezes its session, as on the
  desktop, rather than leaking it at session destroy; the lock-hold watchdog WARNs with its stack
  (`D_loom_holds_the_lock`). Worth a line in SB-Emulators' migration docs, together with the
  monitor case under Soon: a modal shown from inside `synchronized` is a hazard there, not on the
  desktop.
- SB-Emulators' own rule against a library self-registering a Vaadin service init listener needs
  an amendment for `SessionLockCheck`, which `D_loom_servlet` keeps for good reasons
  (`R_service_init_listeners`). Its tests that set up a plain `MockVaadin.setup()` wrap the lock
  now.
- `Q_foreign_tasks`: could `EHelper.callSwing` drop its access-queue drain after `runUntilPark`?
  The drain settles the modal fiber a click woke before the next event (`D_wake_is_access_task`),
  but it also runs whatever foreign access tasks are queued. Untested.
- The epilogue gap (seen reading SB-Emulators, not run): a fiber woken by something other than
  `callSwing`, such as an `executeJs` answer in the clipboard or preferences bridge or a background
  `dispose()`, skips `FieldReconciler.reconcileAll` + `AutoShutdown.checkAppOver`, though
  `FieldReconciler`'s doc says every post-park continuation funnels through `callSwing`. The fix:
  right after each of the four `parkAndAwait` calls returns, arm a one-shot
  `ui.beforeClientResponse(ui, ctx -> epilogue)`. The resumed segment runs in the drain
  (`D_wake_is_access_task`) and the UIDL runs the callback after it (`R_unlock_pushes`), under any
  runner. Arming once per resumption avoids the livelock `FieldReconciler` warns a self-rearming
  callback causes. Keep `callSwing`'s own epilogue: tests read reconciled state right after `_click`,
  and whether Karibu's `_click` runs `beforeClientResponse` is unchecked. Also unchecked: whether
  `checkAppOver` may close the session from inside that callback. The library adds no hook for it,
  since Vaadin's is enough.
- The docs sweep: 47 SB-Emulators files still name `:loom` or its executor.
- A real-browser run of Sampler's dialog and F5 flows. So far everything is Karibu only.
