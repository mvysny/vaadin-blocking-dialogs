# Simplifications in `vaadin-uifiber-loom`

A review pass over the loom runner found no layering to remove, only small cleanups. Replacing
`awaitUninterruptibly` with `CompletableFuture.join()` (23d865d), tidying `VirtualThreadAwareLock`'s
pretend mode, one wrapper around the UI fiber's body, and one carrier pool are already done; the rest follow,
safe and mechanical ones first. Check with `./gradlew`.

## Main code

- **`LoomUtils` repeats the same lazy-caching pattern twice**: the `volatile` `builderConstructor`
  and `runContinuation` are each resolved on first use, each wrapping failure in an ISE naming
  `--add-opens`. `checkAvailable()` runs in the runner's constructor anyway, so one helper for
  "open this, or throw" would remove the duplication. The gain is small.

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
