# The scripted runner, on the SPI

Deferred: SB-Emulators doesn't need it — its tests run the real loom runner under Karibu, driving a
modal by clicking from a second listener (`callSwing(() -> _click(yes))`).

It exists today as `ScriptedBlockingExecutor`, the `BlockingExecutor` the API module's own tests run
on (`BlockingDialogsTest`): no threads, a park plays the next scripted user action — a click that
completes the future — on the test thread and returns; `runUntilPark` runs the whole fiber inline.
It already saves and restores the `CurrentInstance`s around that action, runner-side.

When the API is rebuilt on the SPI, its tests need a runner, and two roads are open:

- **Port it to `UIFiberRunnerSpi`.** The SPI allows it: a fiber keeps one thread, not necessarily
  its own — a runner may run another fiber on it inside a `park()`, provided that fiber ends before
  the park returns; and a parked fiber isn't running, so what the runner plays inside `park()` is
  outside any UI fiber. The cost to the API: around `c.park()` it clears its in-fiber flag, and
  saves and clears the `CurrentInstance`s with it, restoring both on wake before the rebind
  (`D_in_fiber_flag`) — otherwise the scripted click's `runUntilPark` would go inline into the
  parked fiber, and the runner's own check would refuse a legal call. Saved and restored, not
  merely cleared, so the fiber's own entries survive the park. The runner clears its own flag
  inside its `park()`.
- **Test the API on loom instead.** Once loom depends on the SPI only, the API module can take it as
  a test dependency without a cycle — the real runner exercised, parks included, as SB-Emulators does.

## Open questions

- `Q_scripted_worth`: port, or retire it for loom? The classic hazard of a scripted suite: it
  doesn't exercise the real runner. What it gives: no threads, so no races between a click and an
  assertion — the background-thread runner's `Q_karibu_determinism`
  (`blocking-strategy-session-unlock.md`), where the real runner races Karibu's assertions.
