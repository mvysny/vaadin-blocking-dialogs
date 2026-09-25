# A scripted runner for tests

Deferred: SB-Emulators doesn't need it — its tests run the real loom runner under Karibu, driving a
modal by clicking from a second listener (`callSwing(() -> _click(yes))`), and so do ours.

A third `UIFiberRunnerSpi` implementation, test-only: no threads at all.

- `runUntilFirstPark` runs the fiber inline on the caller's thread, to its end.
- `Completable.park()` plays the next scripted user action — a click that completes it — and
  returns.

The SPI already allows it: a fiber keeps one thread, not necessarily its own — a runner may run
another fiber on it inside a `park()`, provided that fiber ends before the park returns; and a
parked fiber isn't running, so code the runner plays inside `park()` is outside any UI fiber.

What it would cost the API: around `c.park()` the API clears its in-fiber flag, and saves and clears
the `CurrentInstance`s with it, restoring both on wake before the rebind (`D_in_fiber_flag`) —
otherwise the scripted click's `runUntilPark` would go inline into the parked fiber, and the
runner's own check would refuse a legal call. Saved and restored, not merely cleared, so the
fiber's own `CurrentInstance` entries survive the park. The runner clears its own flag inside its
`park()`.

## Open questions

- `Q_scripted_worth`: is it worth having at all? The classic hazard: a suite on it doesn't exercise
  the real runner. Its one pull is the background-thread runner's `Q_karibu_determinism`
  (`blocking-strategy-session-unlock.md`), where the real runner races Karibu's assertions.
