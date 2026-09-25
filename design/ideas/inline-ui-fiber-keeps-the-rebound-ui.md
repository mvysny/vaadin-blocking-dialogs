# An inline UI fiber keeps the UI its park rebound to

`RunUntilParkProbeTest.inlineParkAcrossF5`: a UI fiber calls `runUntilPark`, which runs the inner
body inline; the inner body parks across an F5 on a `@PreserveOnRefresh` route. It resumes on the
new UI (`awaitAnchored`'s rebind, `R_preserve_migration`), but once `runUntilPark` returns the
outer UI fiber sees the **old, closed UI**: `runUIFiber`'s `restoreInstances(previous)` undoes the
rebind. `StrategySupport.accessSynchronously`'s inline branch has the same shape.

So "wrap the body" is two shapes, not one:

- **At a fiber's root** — restore on the way out is right: the caller's thread (the scripted runner
  runs the fiber on it) gets its own instances back.
- **Inline** — the outer fiber must keep whatever UI a park inside rebound to. Skip the restore when
  a park rebound the UI, or restore to the rebound one.

SB-Emulators needs it once it adopts the API: a `callSwing` inside a fiber runs inline, and its
modals follow F5 (`BlockingDialogF5Test`'s chained dialog), so after an inline modal the outer
Swing code must see the new UI.

Lands with the API rebuild on the SPI (`spi.md`), where `wrap(ui, body)` is written anew; graduates
into that wrapper's doc comment, and a test beside `inlineParkAcrossF5` that asserts the fix
instead of the bug.
