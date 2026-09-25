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
- **A `CancellationException` from the app's own code is swallowed.** `UIFibers.runReportingErrors`
  ends a UI fiber quietly at DEBUG on *any* `CancellationException`, not only a dead wait's. Example:
  a migrated `actionPerformed` calls `SwingWorker.get()` on a cancelled worker. On the desktop that
  reaches the uncaught-exception handler; here it vanishes.
  - `Q_dead_wait_type`: a library-owned subtype, say `WaitDiedException extends
    CancellationException`, thrown by `AnchorWatch.end()` and the interrupt path. Only that subtype
    ends quietly, and everything else goes to the ErrorHandler. Catch sites that catch
    `CancellationException` keep working.

## Soon

- **A bare park that nobody answers now leaks its session.** SB-Emulators' old executor
  `shutdownNow()`'d every parked fiber on session destroy. SB-Emulators' own parks are anchored now,
  so they unwind (`R_session_destroy_detaches`). A *migrator's* `future.get()` / `latch.await()` on
  the EDT still leaks. So SB-Emulators wants one of:
  - **`loom-holds-the-lock-across-bare-unmounts.md` — preferred.** It is also the faithful Swing
    behaviour, twice over. A bare wait on the EDT freezes the UI, as it does on the desktop. And a
    JDBC call in a listener stops letting other requests in, since the EDT never interleaves
    listeners. That second one is the normal shape of a Swing app, and it is silently interleaved
    today (`R_vt_unmount_releases_lock`). The idea's precondition on SB-Emulators' side is done:
    every park SB-Emulators makes goes through `parkAndAwait`.
  - **`session-destroy-ends-bare-parks.md`**, option C at least, if the above stalls.

## Later

- **`epilogue-before-every-uidl.md`.** The gap it names is real: a fiber woken by an `executeJs`
  answer or a background `dispose()` skips SB-Emulators' epilogue. But the fix is on SB-Emulators'
  side. The library only matters if `Q_epilogue_hook` wants a hook here rather than Vaadin's
  `beforeClientResponse`.
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

- **`run-until-park-settles-woken-ui-fibers.md`.** `runUntilPark` plus SB-Emulators' access-queue
  drain covers it under `D_wake_is_access_task`, and the port's test run shows it. It is ready to
  graduate. `Q_foreign_tasks` is the one question worth keeping: could SB-Emulators drop the drain?
  Untested.
- **`ui-fiber-condition.md`.** `Q_condition_use_case`: no. SB-Emulators' `EventQueue.createSecondaryLoop`
  is a WARN stub, and nested modals each park on their own wait, as that idea says.
- **`testapp.md`.** SB-Emulators' Sampler is its testbed.
- **A published Karibu fixture (`MockVirtualThreadAwareServlet`).** SB-Emulators keeps its own copy,
  and `D_loom_servlet` explains why none is published.
- **The `allowPinningJdk` opt-in.** SB-Emulators refuses Java 21-23 itself at servlet init and never
  sets it, so `D_loom_jdk_gate` stands as is.

## SB-Emulators-side, no library work

Recorded so they aren't mistaken for library asks:

- A dead modal's `CancellationException` now escapes `JOptionPane.show*` into migrated code, where a
  `catch (Exception e)` swallows it and the listener runs on after its session died. SB-Emulators'
  convention for "a blocking call nobody can answer" is its own `Error` subtype, which a
  `catch (Exception)` cannot swallow. Convert at the `Dialog` seam, and keep it out of the
  ErrorHandler during teardown. This is easier once `Q_dead_wait_type` exists.
- The docs sweep: 47 SB-Emulators files still name `:loom` or its executor.
- A real-browser run of Sampler's dialog and F5 flows. So far everything is Karibu only.
