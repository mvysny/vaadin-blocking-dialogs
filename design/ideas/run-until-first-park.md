# A `runLater` that returns once the block parks or ends

Came out of SB-Emulators' experiment of dropping its vendored `:loom` for this library
(2026-09-24). It is the one place where SB-Emulators leans on a loom implementation detail rather
than on the API. Graduates into `BlockingExecutor`'s javadoc plus a line in `D_input_exclusion`.

## What SB-Emulators needs

Every peer→Swing listener in SB-Emulators goes through `EHelper.callSwing(body)`. Today, on this
library, that is:

```java
BlockingDialogs.runLater(body);
session.getService().runPendingAccessTasks(session);   // run the first segment *now*
FieldReconciler.reconcileAll(session);                 // epilogue: needs the segment settled
AutoShutdown.checkAppOver(session);
```

The drain matters in two ways. The emulator code after `callSwing` reads state the block just
wrote, and the epilogue must see the cascade settled. And Karibu's `_click` / `_setValue` don't
flush the access queue; only the next lookup does (the default `awaitBeforeLookup()` runs
`clientRoundtrip()`, Karibu 2.7.3 source). SB-Emulators' tests read emulator state without a
lookup, so without the drain they read stale state mid-cascade.

It works because under loom the block's first segment happens to be a queued `session.access`
task (`SessionCarrier.execute`). Nothing in `BlockingExecutor` promises that. Under session-unlock
the block runs on a worker thread, `runPendingAccessTasks` returns at once, and the epilogue runs
*before* the block. So SB-Emulators would break on a strategy switch, which is exactly what **One
API, any strategy** says must not happen.

## Proposal

A second entry point — name open, `runUntilPark(block)` for now:

> Starts `block` as a block and returns once it parks or ends. Called inside a block, runs it inline.

- **Loom:** start the virtual thread, then drain until its first continuation has returned. Draining
  the whole access queue (what SB-Emulators does) also runs foreign tasks early; a tighter version
  signals a `firstParkOrEnd` future from `SessionCarrier.mount` once the first `continuation.run()`
  returns, and drains only until it completes.
- **Session-unlock:** the request already waits for the block's first park or end
  (`D_input_exclusion`), so the primitive exists internally — this just exposes the wait at the
  call site instead of at the response.

`runLater` keeps its current contract ("starts after the calling listener returns"). The new method
is the stronger promise: "has started, and has run up to its first park".

## Open questions

- `Q_name`: `runUntilPark`, `runNow`, or `run` beside `runLater` — mirroring `UI.accessSynchronously` beside
  `UI.access`?
- `Q_drain_all`: under loom, is draining the whole session queue acceptable (simple, and what
  SB-Emulators measured green on 2174 tests), or is only-until-first-park worth the extra signal?
- `Q_inside_block`: inline inside a block is what SB-Emulators does (`StrategySupport.isInBlock()` →
  `body.run()`). Should the library own that branch, so callers stop needing `isInBlock`?
- `Q_library_tests`: answered — yes, though not after `_click` itself. `LoomBlockingExecutorTest`
  mostly calls `MockVaadin.clientRoundtrip()` right after `runLater`; elsewhere (`nestedDialogs`,
  the testapp's `MainViewTest`) the next `_get` drains through Karibu's lookup hook. That's why
  they never needed this method. The explicit roundtrips after `runLater` are its likely first
  callers.
