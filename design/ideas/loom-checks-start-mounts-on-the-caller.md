# Loom checks that `Thread.start()` mounts the first segment on the caller

`runUntilFirstPark` rests on `VirtualThread.start()` calling `scheduler.execute` synchronously on
the starting thread (`R_vt_scheduler`, [src, JBR 25.0.4]); `SessionCarrier.mountHere` makes that
first submit run in place, under the caller's lock. Nothing checks it holds.

If a JDK moves to a lazy or asynchronous first submit, it is worse than "`runUntilPark` silently
degrades to `runLater`": `mountHere` is still `true` when `execute` finally runs, on whatever thread
the JDK submits from, and `mount` runs the UI fiber's first segment right there **without the
session lock**. `D_input_exclusion` goes with it.

## Sketch

- After `start()` returns, `mountHere` must be `false`; if not, throw `IllegalStateException` naming
  the JDK (`Runtime.version()`, vendor) and `R_vt_scheduler`.
- That throw comes too late for the segment already handed off. So `execute` also mounts in place
  only on the thread that called `start()` (captured before it), and otherwise goes to
  `session.access` like any later submit: safe, if late, and the post-`start()` check reports it.

## Open questions

- `Q_check_when`: per `runUntilFirstPark` (a volatile read, free), or once at startup in
  `LoomUtils.checkAvailable()`: start a throwaway virtual thread on a probe scheduler and fail the
  constructor, like `D_loom_jdk_gate`? Startup fails at `ServiceLoader` time, before any session;
  per-call also catches a JDK that submits lazily only sometimes. Both is cheap.
- `Q_test`: a JDK that submits lazily can't be conjured. Test `SessionCarrier` directly - first
  `execute` from a foreign thread goes to the access queue - which needs it package-private rather
  than a `private` nested class.
