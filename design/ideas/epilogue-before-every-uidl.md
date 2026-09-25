# SB-Emulators' epilogue before every UIDL

Deferred: SB-Emulators doesn't need it now, and the API needs nothing for it.

Found while rejecting Hacky (`D_run_until_park`): SB-Emulators reconciles Swing state onto Vaadin in
an epilogue — `FieldReconciler.reconcileAll(session)`, then `AutoShutdown.checkAppOver(session)` —
right after the outermost `callSwing`'s listener and its access-queue drain. In
`ui.beforeClientResponse(...)` instead it would run before every UIDL, a push's included
(`R_unlock_pushes`), under any runner, and would stop depending on `runUntilPark`.

A gap it would close, seen reading SB-Emulators (2026-09-25, not run): a fiber resumed by a waker
that isn't `callSwing` — an `executeJs` return in the clipboard or preferences bridge, a background
thread disposing a dialog — gets no epilogue at all, though `FieldReconciler`'s doc says every
post-park continuation funnels through `callSwing`.

## Open questions

- `Q_epilogue_hook`: reconcile in `ui.beforeClientResponse(...)`? SB-Emulators' `FieldReconciler`
  already warns that a self-rearming `beforeClientResponse` callback livelocks, so it would be
  armed once per change, not per response. Does the API offer a "before every park" hook for it,
  or is Vaadin's own hook enough?
