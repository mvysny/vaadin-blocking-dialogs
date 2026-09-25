# Simplifications in `vaadin-uifiber-loom`

A review pass over the loom runner found no layering to remove, only small cleanups. Replacing
`awaitUninterruptibly` with `CompletableFuture.join()` (23d865d) and tidying `VirtualThreadAwareLock`'s
pretend mode are already done; the rest follow,
safe and mechanical ones first. Check with `./gradlew`.

## Main code

- **The body is wrapped twice**: `runUntilFirstPark` wraps it in `enterUIVirtualThread` /
  `exitUIVirtualThread`, and `SessionCarrier`'s constructor wraps it again in
  `current.set` / `remove`. Pass the lock into `SessionCarrier` and do both in one try/finally.
- **`SessionCarrier.current()` has a throw that can't be reached**: `newCompletable` has already
  checked `isUIVirtualThreadOf`. `Objects.requireNonNull(current.get())` would do.
- **`LoomUtils` repeats the same lazy-caching pattern twice**: the `volatile` `builderConstructor`
  and `runContinuation` are each resolved on first use, each wrapping failure in an ISE naming
  `--add-opens`. `checkAvailable()` runs in the runner's constructor anyway, so one helper for
  "open this, or throw" would remove the duplication. The gain is small.
- **Two carrier pools could be one**: `LoomUIFiberRunner.Handoff` and
  `LoomUtils.InheritedThreadCarriers` are both cached daemon platform pools for continuations that
  don't run on the session. One pool in `LoomUtils` removes a holder class.

## Tests

- **Shared test helpers.** `queueARequest`, which spin-waits until a platform thread is `WAITING`
  on the session lock, is copied almost verbatim in `IoUnmountTest` and `VirtualDrainerProbeTest`.
  "`session.unlock()`, do something, `session.lock()` in finally" appears about 5 times across the
  three test classes, and the `routes` + `MockVaadin.setup(..., new MockVirtualThreadAwareServlet(routes))`
  setup is repeated in each. Move these into a static helper class, not an abstract base test,
  since inheriting just to share code is off the table.
- **Rename `VirtualDrainerProbeTest` → `VirtualDrainerTest`**: "Probe" sounds like a research
  experiment, but it is now a regression test.

## Leave as is

- `LockHoldWatchdog.take()`: its wait loop with growing warning intervals is about as simple as that
  behaviour gets.
- `SessionCarrier.mountHere` / `releasing`: both are needed; neither can be derived reliably from
  the thread's state.
- The `getSessionLock` override repeated in `MockVirtualThreadAwareServlet`: it's forced by the
  different superclass (`MockService`).

## Open questions

- `Q_merge_pools`: merging loses the two thread-name prefixes (`blocking-dialogs-handoff-`,
  `blocking-dialogs-inherited-carrier-`), which tell apart the two kinds of carrier in a thread
  dump. Worth one class less?
