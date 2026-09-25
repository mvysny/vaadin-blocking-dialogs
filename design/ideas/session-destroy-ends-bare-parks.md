# Session destroy ends (or at least reports) the UI fibers it would otherwise strand

Came out of SB-Emulators' experiment of dropping its vendored `:loom` for this library
(2026-09-24). Graduates as a `D_` amendment to `D_anchored_wait` if option A or C lands, or as a
one-line why-not under `D_anchored_wait` if rejected.

## What happened

SB-Emulators' modal `Dialog.show()` parked with a bare `CountDownLatch.await()` inside a UI fiber.
Its old `:loom` ran UI fibers on a session-scoped `ExecutorService` that it `shutdownNow()`'d on
session destroy, so the interrupt unwound a parked modal (`R_vt_scheduler`, last bullet). On this
library the same park stayed `WAITING` after `MockVaadin.tearDown()`, indefinitely. Switching the
park to `UIFibers.parkAndAwait(dialogPeer, future)` fixed it:
`R_session_destroy_detaches` detached the peer, `AnchorWatch` cancelled the future, and the UI fiber
unwound. So `D_anchored_wait` holds up. The leak came from leaning on the bare park that
`D_pluggable_runner` forbids.

## Why it is still worth something

The forbidden bare park is **silent, and only under loom**:

- Under loom it works in every test: the dialog opens, the answer arrives, the UI fiber goes on. It
  goes wrong only when nobody answers — session destroy, a closed tab — and then nothing happens.
  With `jdk.trackAllThreads` at its default (true), the root thread container holds every
  virtual thread strongly [src, JDK 25 `jdk.internal.vm.ThreadContainers`].
  So the parked thread is reachable forever, together with its stack, the UI tree and the session
  that stack references. One leaked session per unanswered bare park.
- Under session-unlock a bare park holds the session lock, so the UI freezes at once. That is
  loud, and so it gets fixed in development.

A migrator porting to this library will write `future.get()` / `latch.await()` in a UI fiber at some
point, because loom lets them.

## Options

**A. Backstop.** On session destroy, interrupt every UI fiber thread of that session. A park inside
`parkAndAwait` already turns the interrupt into `WaitDiedException`; a bare park sees
`InterruptedException`. The registry is a session attribute holding a weak set of UI fiber threads.
The runner sees every park, so it keeps the registry. The destroy listener is registered lazily
on the first `runLater` of a session (`service.addSessionDestroyListener`), or from the
`VaadinServiceInitListener` the loom jar ships anyway (`SessionLockCheck`), so
`D_anchored_wait`'s objection to a `requestEnd` hook doesn't apply here. But this
directly reverses the entry's "no backstop either", and it duplicates the detach signal as state,
which is exactly what that entry refuses. It is loom-only in effect: under session-unlock the
destroy can't even run while a bare park holds the lock.

**B. Tripwire at park time.** The loom carrier knows when a continuation returns with its virtual
thread still alive: right after `continuation.run()` in `SessionCarrier.mount`. If the UI fiber is not
inside a `Completable.park()` at that moment, it parked bare, so WARN with the parked thread's
stack, once per call site. Enforces `D_pluggable_runner` without a registry. **Probably
unworkable:** every unmount looks the same to the carrier. A contended `ReentrantLock`, a
`Thread.sleep`, and socket IO (a JDBC query in a UI fiber) all unmount. So B false-positives on
ordinary IO. See `Q_io_unmount`.

**C. Tripwire at destroy.** The registry from A, but it only reports. On session destroy, WARN for
every UI fiber thread of the session that is still alive and not parked in a `Completable.park()`, with its
stack ("this UI fiber parked outside `parkAndAwait`; it will never end"). No false positives from IO
(a UI fiber mid-query at the moment of destroy is rare, and still worth a line). It keeps
`D_anchored_wait`'s promise — the library still kills nothing from outside — and makes the leak
visible where it happens.

Leaning: **C**, or **A + C** (interrupt *and* report) if the leak is judged worse than reversing
`D_anchored_wait`.

## Open questions

- `Q_backstop_or_tripwire`: C alone, or A + C? C diagnoses the bug but leaves the leak in
  production. A + C fixes the symptom too, at the cost of a registry plus the reversal of
  `D_anchored_wait`.
- `Q_interrupt_semantics`: "fire `CancellationException` in every parked thread" is only possible
  through interrupt. Nothing can throw into another thread, and a bare park surfaces the interrupt as
  `InterruptedException` or swallows it. Is that close enough, or should the backstop only ever
  target `parkAndAwait` parks, which are already covered by detach? In that case A buys nothing
  and C is the whole idea.
- `Q_io_unmount`: measured true — `R_vt_unmount_ends_segment` — and now moot: loom holds the lock
  across every unmount but a park (`D_loom_holds_the_lock`), so a bare park freezes the session at
  once, as under session-unlock, and the lock-hold watchdog WARNs with its stack. The silent leak
  this idea is about is gone; what's left is a session destroyed while a bare park freezes it.
  Probably retire this file.
