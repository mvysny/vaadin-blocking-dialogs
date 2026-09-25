# `runUntilPark` also settles the UI fibers it woke

`runUntilPark` (`D_run_until_park`) returns once *its own* UI fiber parks or ends. SB-Emulators
needs one more thing: every UI fiber that its UI fiber *woke* — a dialog's parked UI fiber, resumed by the
OK click's UI fiber completing its future — must also have run up to its next park or end. Graduates
into `D_run_until_park` and `UIFibers.runUntilPark`'s javadoc.

**Superseded in part** by `D_wake_is_access_task`: a wake-up is an access task, settled in the
session's drain rather than a scope, so the mechanism sketch and `Q_scope_membership` below are
moot. Kept for the evidence and side findings until graduated.

## Evidence (2026-09-24)

- `RunUntilParkProbeTest.aUIFiberWokenByTheCallIsNotSettledWhenItReturns`: UI fiber A parks on
  `answer`; `runUntilPark(() -> answer.complete("yes"))` returns with A still unresumed. A runs only
  at the next roundtrip. The library's own `RunUntilPark.returnsOnceTheUIFiberParks` shows the same
  shape: `_fireConfirm` needs a `clientRoundtrip()` after it.
- SB-Emulators on `runUntilPark` without its access-queue drain: 40 of 2174 `:emulators` tests
  fail, every one of them a modal resume (`JOptionPaneTest`, `JFileChooserTest`,
  `JColorChooserTest`, `FileDialogTest`, `JTableModalEditorTest`, `BlockingDialogF5Test`, …).
  `JTableModalEditorTest.parkedCommitHasSettledWhenTheClickReturns` states the contract outright.
  With the drain kept, all 2174 pass.

## Why it is the right promise, not an SB-Emulators quirk

In Swing a modal's `setVisible(true)` runs a secondary event loop on the caller's stack. The OK
click is dispatched in it, the loop exits, and the code after `setVisible(true)` runs **before the
next event is dispatched**. "The click's listener returned" therefore implies "the dialog's caller
has moved on". A Vaadin app gets the same intuition from `UI.accessSynchronously`: when it
returns, what it caused has happened. SB-Emulators gets there today only by draining the whole
access queue, which works under loom by accident: a woken continuation happens to be a queued
access task (`SessionCarrier.execute`). Under session-unlock the woken UI fiber is a worker thread,
so a drain does nothing for it — the same **One API, any runner** hole that `D_run_until_park`
closed for the first segment.

## Mechanism sketch

**Loom.** While `runUntilPark` runs on its platform caller, a submit made *by one of this session's
UI fibers* goes to a scope-local queue instead of `session.access`. The caller mounts the first
continuation, then keeps mounting from that queue until it is empty. The loop is transitive: A
wakes B, and B wakes C. Submits from other threads (a background job completing a future) still go
to `session.access`, so the scope stays deterministic. The continuation can't mount on the
submitting virtual thread, which is why the caller does the mounting.

**Session-unlock.** Each UI fiber woken inside the scope registers with it. The caller releases its
holds and waits until every registered UI fiber has parked or ended, then re-takes the holds — the
first-park wait of `D_input_exclusion`, over a set of UI fibers instead of one.

## Open questions

- `Q_scope_membership`: only submits made on a UI fiber's own thread while the scope runs, or also
  unparks from foreign threads that land during it? The first is deterministic, the second racy.
- `Q_runLater_inside`: a `runLater` issued inside the scope — settle it too (SB-Emulators' drain
  does), or leave it queued as `runLater` promises?
- `Q_foreign_tasks`: SB-Emulators' drain also runs plain `ui.access` tasks queued during the
  cascade. The 2174-test run says nothing needs that beyond woken UI fibers. Confirm this after the
  change, with the drain removed.

## Side findings from the same review

- **Inline branch, stale UI after F5**: fixed, `UIFibersTest`'s `insideAUIFiberKeepsTheUIAParkReboundTo`.
- **IO counts as a park**: `RunUntilParkProbeTest.ioBeforeTheFirstDialog`. `runUntilPark` returns
  at a socket read before the dialog opens, because `Thread.start()` returns at *any* unmount
  (`R_vt_unmount_releases_lock`). Fixed as a side effect by
  `loom-holds-the-lock-across-bare-unmounts.md`.
- **Tripwire on `mountHere`**: the synchronous first mount rests on `VirtualThread.start()`
  submitting on the caller (`R_vt_scheduler`, [src]). If a JDK moves to a lazy submit,
  `runUntilPark` silently degrades to `runLater`. Assert after `start()` that `mountHere` was
  consumed, and throw naming the JDK otherwise.
- **Inline exceptions**: `D_run_until_park` cites `UI.accessSynchronously` as its naming model, but
  that method's inline path *propagates* to the caller, while `runUntilPark`'s inline path reports
  and continues the outer UI fiber (`exceptionsGoToTheErrorHandlerInlineToo`). SB-Emulators keeps
  propagation (its own inline branch), since a nested Swing listener's exception unwinds the outer
  dispatch. Worth one sentence in `D_run_until_park` on why the analogy stops there.
