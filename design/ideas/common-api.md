# The strategy-neutral API in `vaadin-blocking-dialogs`

What app code is written against, so that switching strategy is swapping one dependency
(**One API, any strategy**, `D_pluggable_strategy`). Graduates when the loom strategy implements it:
the shape goes to doc comments; the lifetime model (a wait lives as long as its anchor) and the
threading contract to `design/architecture.md`; the "why an anchor, why no scope" reasoning to a `D_`;
the verified Flow detach/attach order to an `R_`; a "Beyond dialogs" section to the README.

## Decided (grilled 2026-09-24)

**There is an API; "just block on the future" is loom-only.** Under session-unlock the worker is a
platform thread that really holds the session lock, so a bare `future.get()` parks with the lock
held: nothing is pushed, and the answering click blocks on `session.lock()` forever. So the park
point is a library call, and so is the entry onto a blocking-capable thread.

**Framing: "blocking dialogs", general underneath.** The primitives are dialog-neutral — an app can
park on any future anchored to any component (a Save button showing its own progress bar, a
background job) — but the name and pitch stay on dialogs, which is what people search for. The
README gets a short "Beyond dialogs" section.

**Shape.** One interface, `BlockingExecutor`:

```java
public interface BlockingExecutor {
    /** The strategy on the classpath, via ServiceLoader. Throws on none, and on more than one. */
    static BlockingExecutor get();

    /** From a thread holding the session lock with UI.getCurrent() set; throws otherwise. */
    void runLater(Runnable block);
    /** ui.access(() -> runLater(block)); for background threads. */
    void access(UI ui, Runnable block);
    /** Runs block and waits for it, parks included; see the threading contract below. */
    void accessSynchronously(UI ui, Runnable block);
    <T> T accessSynchronously(UI ui, Supplier<T> block);

    /** Parks the calling block until future completes; anchor = whose life this wait belongs to. */
    <T> T parkAndAwait(Component anchor, CompletableFuture<T> future);
    /** Throws unless the calling thread may park, i.e. it is inside a block. Public. */
    void checkUIThreadWithBlockingCapabilities();
}
```

The app may also instantiate a strategy's executor directly; `get()` is the zero-wiring path.

**Call site: the static facade `BlockingDialogs`.** `BlockingExecutor` is the SPI a strategy
implements; app code calls the final class `BlockingDialogs` — the same methods as statics, plus the
two `showAndAwait` helpers, which live only on the facade so no strategy implements or overrides them:

```java
button.addClickListener(e -> BlockingDialogs.runLater(() -> {
    if (BlockingDialogs.showAndAwait(dlg) == CONFIRM) { … }
}));
```

Routing: `runLater` and `access` go through `BlockingExecutor.get()` — they are called outside any
block. `parkAndAwait`, `showAndAwait` and `accessSynchronously`-from-inside-a-block use **the calling
block's own executor**, found through the thread-local set when the block starts (the one
`checkUIThreadWithBlockingCapabilities()` reads). So a manually instantiated executor works with
every helper, and nothing inside a block depends on the SPI.

**Strategy selection: SPI, exactly one.** `get()` finds the strategy through `ServiceLoader` and
throws when there is none or more than one. Hence one demo app per strategy (`testapp.md`). `get()`
caches its answer (a lazy holder): a `ServiceLoader` instance caches the providers it instantiated,
but every `ServiceLoader.load(...)` is a new loader that re-scans `META-INF/services`, and `get()` sits
on the hot path of every `runLater`. A strategy is stateless app-wide, so one per classloader is right;
the "none / more than one" failure is cached too, so it throws the same way on every call.

**Lifetime: a block lives as long as the future it is parked on; the future as long as its anchor.**
No executor scope — no per-UI, per-session or per-tab registry, nothing that kills parked threads
from outside:

- `parkAndAwait(anchor, future)` watches the anchor. On detach it does not decide at once; it queues
  `session.access(() -> { if (!anchor.isAttached()) future.cancel(false); })`. Access tasks run on
  the ultimate unlock, after the current request is fully handled.
- **F5 with `@PreserveOnRefresh` is a synchronous migration** — detach from the old UI, attach to
  the new one, in one call under the lock (`R_preserve_migration`). So by the time the queued check
  runs, a migrated anchor is attached again, a dead one is not. Source-read only; a Karibu test pins
  it at implementation.
- **Resume UI:** after waking, the block's `UI.getCurrent()` / `VaadinSession.getCurrent()` are
  rebound to `anchor.getUI()`, which follows a migration by construction. No window-name lookup.
- No anchorless overload: a wait that names no owner can only leak.
- **Session destroy and tab close need no backstop:** both detach the anchor, and the check queued
  from our detach listener runs in the same access-queue drain (`R_session_destroy_detaches`). Only
  the timing of a tab close varies — a closed `@PreserveOnRefresh` tab waits for heartbeat expiry
  (`R_preserve_migration`). Free under loom; under session-unlock a worker is held that long.

**Cancellation means "the wait is dead", only.** A user-facing Cancel is an *answer*: the dialog
completes the future with a cancel value (`false`, `null`, an enum). So:

- `parkAndAwait` throws plain `CancellationException` when the future is cancelled; the `runLater`
  wrapper swallows it at the top of the block (debug log at most) — `finally` blocks run on the way.
- An interrupted park restores the interrupt flag and throws `CancellationException` too: one exit.
- A failed future's `ExecutionException` is unwrapped and the cause rethrown as-is.
- Anything else escaping a `runLater` block goes to the session `ErrorHandler`, with the UI current.
- The sharp edge to document: a progress dialog's Cancel must not `cancel()` the future the block
  awaits, or the block dies silently. Await an *outcome* future; Cancel stops the job *and*
  completes the outcome with "cancelled".

**Threading contract.**

- `runLater` only from a thread holding the session lock with `UI.getCurrent()` set — a listener,
  or code inside `ui.access()`; background threads use `access(ui, block)`. Returns `void`: nobody
  sensibly waits on a block; blocks hand results to each other through futures.
- `runLater` inside a running block queues a new, independent block, which starts once the current
  block parks or ends — identical under both strategies (loom: the first continuation waits for the
  lock via `ui.access`; session-unlock: the worker waits on `session.lock()`). A listener fired
  synchronously inside a block therefore runs its body at the next park — Swing's `invokeLater`.
- `accessSynchronously(ui, block)`: inside a block, runs inline (may park — nested blocking); from a
  background thread without the lock, queues a block and waits for it, parks included (a job asking
  the user mid-way); from a lock-holding thread outside a block, throws — it would deadlock under
  both strategies. Exceptions, `CancellationException` included, go to the caller, not the
  `ErrorHandler`, as with Vaadin's own `accessSynchronously`.

**Input exclusion: no other request of the session runs between `runLater` and the block's first
park or end.** The double-clicked Save button, and any double action: Swing's "modal blocks input
from `setVisible(true)`". Loom gives it by construction — the block's first segment runs inside the
click request's ultimate unlock, before the lock is really released (`R_unlock_pushes`), so the second
click finds a modal dialog open and Vaadin's server-side modality drops it. Session-unlock owes an
implementation: its `runLater` makes the listener's request thread release its holds, wait for the
worker's first park or end, re-take the holds and respond, so the second click queues behind the
worker — a short, bounded wait before the response, not the endless park of
`R_async_push_no_response`; it needs a probe. Accepted: a first segment that ends without parking
releases the lock like any listener, and a later click then runs the listener again — ordinary Vaadin
behaviour. The testapp's double-click scenario pins it for both strategies.

**Serialization: a parked block does not survive it, period.** A parked thread cannot be
serialized, so a session with an open blocking wait does not survive session persistence or
replication; after deserialization the wait is gone. The README states it as a limit. The anchor's
detach listener is the one thing of ours in the component tree; it must not drag a
`CompletableFuture` or a thread into the session's serialized form (`transient`, or a listener that
tolerates a null future after deserialization).

**Thread-locals.** A block runs on one thread for its whole life under both strategies, so
thread-locals it sets survive every park. What never reaches it are the listener's request-thread
thread-locals: inside a block `UI` and `VaadinSession` are current (rebound after each park) and
`VaadinRequest` / `VaadinResponse` are always `null` — Vaadin's background-thread contract. Anything
request-scoped is captured before `runLater`. Loom *could* copy the resuming carrier's request in;
session-unlock can't, so it doesn't.

**Loom's session-lock wiring: a servlet plus the hook.** `getSessionLock()` is a protected method
of `VaadinService`, so the app must subclass something. `-loom` ships `LoomVaadinServlet` (its service
overrides `getSessionLock`) **without `@WebServlet`**, so it never auto-registers; a Vaadin Boot app
writes `@WebServlet(urlPatterns = "/*", asyncSupported = true) class MyServlet extends
LoomVaadinServlet {}`. Apps with their own service class (Spring's `SpringVaadinServletService`) call
the static `VirtualThreadAwareLock.wrap()` from their own override. The loom executor keeps
refusing to start on an unwrapped lock, naming the fix. Rejected: pre-seeding the lock from an
`HttpSessionListener` — needs the service name up front, loses Vaadin's instrumented lock
(`SessionLockListener`), relies on the container scanning a `@WebListener` in a library jar (Spring
Boot doesn't), and `VaadinSession.refreshLock()` forbids swapping the lock later. **Tests:** no
published Karibu artifact — an app may use Vaadin's official UI unit testing instead; the README
shows the `getSessionLock` override for a mocked service. `MockVirtualThreadAwareServlet` stays an
unpublished test fixture for this repo's own tests.

**Loom on JDK 21-23: fail fast, conscious opt-in.** `-loom` compiles for 21; creating a loom
executor on JDK < 24 throws `IllegalStateException` naming JEP 491 and the deadlock (`R_vt_pinning`),
unless `-Dblockingdialogs.loom.allowPinningJdk=true` is set — for shops stuck on 21 LTS that accept
the risk. CI's JDK 21 job sets it, so the loom tests still run there; the `@EnabledForJreRange(24)`
gate on the `synchronized` tests stays. Rejected: `--release 25` (the same protection with a cryptic
`UnsupportedClassVersionError` and no way out), a warning or README-only (how vaadin-loom#2 happened).

**Helpers: exactly two overloads.** Vaadin has no common "openable" type (`ConfirmDialog extends
Component`, not `Dialog`; `Dialog`, `ConfirmDialog`, `Notification` each declare their own
`open()`/`close()`), so a helper is per type:

- `showAndAwait(Dialog dialog, CompletableFuture<T> answer): T` — opens, parks with the dialog as the
  anchor, closes in `finally`; the app builds the dialog and its buttons complete `answer`.
- `showAndAwait(ConfirmDialog dialog): ConfirmDialogOutcome` — the app configures the dialog fully
  (texts, which buttons, themes, custom button components); the library adds three listeners mapping
  `ConfirmEvent` / `RejectEvent` / `CancelEvent` (Cancel button *or* Escape) onto the enum
  `CONFIRM` / `REJECT` / `CANCEL` and awaits. An enum rather than the event object: the events are
  not a sealed hierarchy, so a `switch` over them loses exhaustiveness. Server-side there is no
  `Button` to return — the default buttons are declared by text.

No `confirm(message)`, Yes/No/Cancel or text prompt: every app builds its own dialogs, and each
helper would replicate `ConfirmDialog`'s API. A dialog with a fourth button, or any other openable,
is the app's own two lines around `parkAndAwait`. The progress-dialog-around-a-job helper lives in
the testapp as an example, since apps will style it their own way.

## Open questions

None left from the grilling; new ones go here.
