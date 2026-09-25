# SB-Emulators on this library: what it needs, graded

SwingBridge Emulators (SB-Emulators) dropped its vendored `:loom` for `vaadin-blocking-dialogs` +
`vaadin-uifiber-loom` 0.1-SNAPSHOT from `~/.m2` (2026-09-25, branch `blocking-dialogs-on-uifiber`,
commit `e5ae070`). Karibu only, no real browser yet: all 2182 `:emulators` tests pass, and so do
22 printing, 7 Spring and 92 Sampler tests.

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
  (`D_loom_holds_the_lock`). Worth a line in SB-Emulators' migration docs.
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
