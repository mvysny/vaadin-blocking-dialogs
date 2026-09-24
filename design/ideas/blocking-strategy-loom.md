# Blocking strategy: loom, on the strategy-neutral API

What is left of the common-API grill (2026-09-24) once `vaadin-blocking-dialogs` landed: the API, its
contracts and the shared half (`StrategySupport`) live in the code and its doc comments, the
reasoning in `D_anchored_wait`, `D_spi_exactly_one`, `D_input_exclusion`, `D_two_helpers`. This file
keeps what `vaadin-blocking-dialogs-loom` still owes. Graduates when that module implements
`BlockingExecutor`: the wiring and the JDK gate to doc comments and the README's requirements, the
"why a servlet" and "why fail fast" reasoning to `D_`s.

## The implementation

`runLater` / `parkAndAwait` follow the shape in `BlockingExecutor`'s class doc: the block's virtual
thread runs `StrategySupport.runBlock`, the park is `awaitAnchored(anchor, future, future::get)` —
`get()` unmounts the virtual thread, and the carrier's `ui.access()` ends. Port from `../vaadin-loom`.

- **Input exclusion comes by construction** (`D_input_exclusion`): the first segment runs inside the
  click request's ultimate unlock. Nothing to build; the testapp's double-click scenario pins it.
- **Request thread-locals stay out.** Loom *could* copy the resuming carrier's `VaadinRequest` in;
  session-unlock can't, so neither does (`BlockingExecutor.runLater`'s doc promises `null`).

## Loom's session-lock wiring: a servlet plus the hook

`getSessionLock()` is a protected method of `VaadinService`, so the app must subclass something.
`-loom` ships `LoomVaadinServlet` (its service overrides `getSessionLock`) **without `@WebServlet`**,
so it never auto-registers; a Vaadin Boot app writes `@WebServlet(urlPatterns = "/*",
asyncSupported = true) class MyServlet extends LoomVaadinServlet {}`. Apps with their own service
class (Spring's `SpringVaadinServletService`) call the static `VirtualThreadAwareLock.wrap()` from
their own override. The loom executor keeps refusing to start on an unwrapped lock, naming the fix.
Rejected: pre-seeding the lock from an `HttpSessionListener` — needs the service name up front, loses
Vaadin's instrumented lock (`SessionLockListener`), relies on the container scanning a `@WebListener`
in a library jar (Spring Boot doesn't), and `VaadinSession.refreshLock()` forbids swapping the lock
later (`R_vt_lock_identity`).

**Tests:** no published Karibu artifact — an app may use Vaadin's official UI unit testing instead;
the README shows the `getSessionLock` override for a mocked service. `MockVirtualThreadAwareServlet`
stays an unpublished test fixture for this repo's own tests.

## Loom on JDK 21-23: fail fast, conscious opt-in

`-loom` compiles for 21; creating a loom executor on JDK < 24 throws `IllegalStateException` naming
JEP 491 and the deadlock (`R_vt_pinning`), unless `-Dblockingdialogs.loom.allowPinningJdk=true` is
set — for shops stuck on 21 LTS that accept the risk. CI's JDK 21 job sets it, so the loom tests
still run there; the `@EnabledForJreRange(24)` gate on the `synchronized` tests stays. Rejected:
`--release 25` (the same protection with a cryptic `UnsupportedClassVersionError` and no way out), a
warning or README-only (how vaadin-loom#2 happened).

## Tests the scripted strategy can't play

The API module's `ScriptedBlockingExecutor` covers everything but the park itself. What Vaadin
forbids from inside an access task — a session destroy trips `verifyNoOtherSessionLocked` — goes
here, where the block's virtual thread parks and the test thread is free:

- **Session destroy** cancels a parked wait at once: the noted UI is closing (`R_session_destroy_detaches`).
- **Tab close** (`removeClosedUIs`) cancels it — at once, or, reaped during another tab's request,
  at that request's response.
- **F5 on a `@PreserveOnRefresh` route** keeps the wait, a migrated dialog's and a view's alike
  (`R_preserve_migration`), and resumes on the new UI.

## Open questions

None open; new ones go here.
