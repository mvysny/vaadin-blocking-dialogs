# The strategy-neutral API in `vaadin-blocking-dialogs`

What app code is written against, so that switching strategy is a wiring change
(**One API, any strategy**, `D_pluggable_strategy`). Graduates when the first strategy implements it:
the shape goes to doc comments, cross-module wiring to `design/architecture.md`.

## Starting sketch

vaadin-loom's shape, lifted: something runs a block that is allowed to block, and something inside
the block does the blocking.

```java
public interface BlockingExecutor extends AutoCloseable {
    /** Runs block later, under the session lock, on a thread that may block. Returns immediately. */
    void run(@NotNull Runnable block);
    /** Parks the calling block until future completes, the browser staying live meanwhile. */
    <T> T await(@NotNull CompletableFuture<T> future);
    /** Kills every parked block. Idempotent. */
    @Override void close();
}
```

plus helpers that are pure API, no strategy in them — `confirm(String): boolean` is vaadin-loom's
`MainView.confirmDialog`, which only needs `await`.

Loom implements `await` as `assertUIVirtualThread(); return future.get();`. Session-unlock
implements it as the unlock/park/relock dance. That asymmetry is exactly why `await` must be in the
API: a bare `future.get()` works under loom and deadlocks under session-unlock.

## Open questions

- **`Q_await_lookup`** — how does `confirm()`, deep in app code, find the executor? Pass it down
  (honest, but every helper grows a parameter), or a thread-local "current executor" that the
  strategy sets on the block's thread (Swing-like: `JOptionPane.showConfirmDialog` is static)? Leaning
  thread-local; it also gives a clean "you are not in a blocking block" error.
- **`Q_executor_lifecycle`** — vaadin-loom creates one executor per view, on attach, closed on
  detach. Alternatives: one per `UI`, created lazily and closed on UI detach
  (`ComponentUtil.setData`), so that no view has to manage it. Per-UI is what a Swing port wants;
  per-view kills a dialog when its view is navigated away from, which may be the more correct
  default. Maybe both.
- **`Q_strategy_selection`** — how an app picks a strategy: construct the implementation class
  directly (`new LoomBlockingExecutor(ui)`), a `ServiceLoader`, or a factory the app registers once
  in its `VaadinServiceInitListener`. The testapp wants two strategies in one JVM, one per route,
  which rules out a single global choice.
- **`Q_close_semantics`** — what a parked block sees when its executor closes (UI detached, session
  expired): loom currently interrupts it and swallows the resulting `RuntimeException(InterruptedException)`.
  Make it a named exception of the API, so `finally { dialog.close(); }` runs and nothing is
  reported as an error, identically on both strategies.
- **`Q_error_routing`** — an exception escaping a block goes to the session's `ErrorHandler`
  (vaadin-loom does this). Keep, and state it as API contract.
- **`Q_dialog_helpers`** — which helpers ship: `confirm`, a Yes/No/Cancel, a text prompt returning
  `String`, and a generic "open this `Dialog`, return what completes this future" that the others
  are built on? Or only the generic one, leaving the ready-made dialogs to apps?
- **`Q_servlet_wiring`** — loom needs `VirtualThreadAwareLock.wrap()` in the app's `VaadinService`.
  Ship a ready `VaadinServlet` subclass, a `VaadinServletService` subclass, or only the static hook
  plus a README snippet? Spring apps have their own service class, so the hook must stay usable alone.
- **`Q_runtime_guard`** — should creating a loom executor on JDK < 24 fail fast (`R_vt_pinning`), or
  log a warning, or leave it to the README?
