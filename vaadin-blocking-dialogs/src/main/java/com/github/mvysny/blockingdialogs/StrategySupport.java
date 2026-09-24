/*
 * Copyright 2026 Martin Vysny
 *
 * Licensed under the MIT License. See the LICENSE file in the project root
 * for the full license text.
 */
package com.github.mvysny.blockingdialogs;

import com.vaadin.flow.component.AttachEvent;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.DetachEvent;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.internal.CurrentInstance;
import com.vaadin.flow.server.ErrorEvent;
import com.vaadin.flow.server.ErrorHandler;
import com.vaadin.flow.server.VaadinSession;
import com.vaadin.flow.shared.Registration;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Serializable;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.function.Supplier;

/**
 * The strategy-neutral half of every {@link BlockingExecutor}: what a block is, and what a park does
 * besides parking. A strategy runs each block through {@link #runBlock} on the thread that owns the
 * block, and each park through {@link #awaitAnchored}; see {@link BlockingExecutor} for the shape.
 * App code has no use for this class.
 * <p>
 * A strategy owes two things these methods rely on: a block runs on one thread for its whole life,
 * and it runs holding the session lock except inside {@link Park#park()}.
 */
public final class StrategySupport {
    @NotNull
    private static final Logger log = LoggerFactory.getLogger(StrategySupport.class);

    /**
     * Set while a block runs on this thread; {@code null} otherwise, never {@code false}.
     */
    @NotNull
    private static final ThreadLocal<Boolean> inBlock = new ThreadLocal<>();

    private StrategySupport() {
    }

    /**
     * @return whether the calling thread runs a block, and so may park.
     */
    public static boolean isInBlock() {
        return inBlock.get() != null;
    }

    /**
     * The check {@link BlockingExecutor#runLater} starts with.
     *
     * @return {@link UI#getCurrent()}, the UI the new block runs in.
     * @throws IllegalStateException unless the calling thread holds the session lock with
     *                               {@link UI#getCurrent()} set.
     */
    @NotNull
    public static UI checkLockedUI() {
        final UI ui = UI.getCurrent();
        final VaadinSession session = ui == null ? null : ui.getSession();
        if (session == null || !session.hasLock()) {
            throw new IllegalStateException("runLater() needs the session lock and UI.getCurrent(), which "
                    + Thread.currentThread() + " doesn't have. From a background thread, call access(ui, block)");
        }
        return ui;
    }

    /**
     * Runs {@code block} as a block, on the calling thread. While it runs, {@link #isInBlock()} holds,
     * and {@code ui} with its session and service are current. A {@link CancellationException}
     * escaping the block ends it quietly; any other {@link Throwable} goes to the session's
     * {@link ErrorHandler}, the block's UI current.
     *
     * @param ui the UI {@code runLater()} was called for.
     */
    public static void runBlock(@NotNull UI ui, @NotNull Runnable block) {
        Objects.requireNonNull(block);
        final Map<Class<?>, CurrentInstance> previous = CurrentInstance.setCurrent(ui);
        // only a test strategy nests blocks on one thread; a real one starts each on a fresh thread
        final Boolean wasInBlock = inBlock.get();
        inBlock.set(Boolean.TRUE);
        try {
            block.run();
        } catch (CancellationException e) {
            log.debug("The wait of a block died, so the block ended", e);
        } catch (Throwable t) {
            // not ui: a park may have rebound the block to its anchor's new UI
            handleError(t);
        } finally {
            if (wasInBlock == null) {
                inBlock.remove();
            }
            CurrentInstance.restoreInstances(previous);
        }
    }

    private static void handleError(@NotNull Throwable t) {
        final VaadinSession session = VaadinSession.getCurrent();
        final ErrorHandler errorHandler = session == null ? null : session.getErrorHandler();
        if (errorHandler == null) {
            log.error("A block failed, and there is no session ErrorHandler to report it to", t);
        } else {
            errorHandler.error(new ErrorEvent(t));
        }
    }

    /**
     * The whole of {@link BlockingExecutor#accessSynchronously(UI, Supplier)}; a strategy has no reason
     * to override that.
     */
    static <T> T accessSynchronously(@NotNull BlockingExecutor executor, @NotNull UI ui, @NotNull Supplier<T> block) {
        Objects.requireNonNull(block);
        final VaadinSession session = ui.getSession();
        if (isInBlock()) {
            if (session != VaadinSession.getCurrent()) {
                throw new IllegalStateException("A block holds the lock of " + VaadinSession.getCurrent()
                        + ", so it can't wait for a block of " + ui + " in another session");
            }
            final Map<Class<?>, CurrentInstance> previous = CurrentInstance.setCurrent(ui);
            try {
                return block.get();
            } finally {
                CurrentInstance.restoreInstances(previous);
            }
        }
        if (session != null && session.hasLock()) {
            throw new IllegalStateException(Thread.currentThread() + " holds the session lock outside a block,"
                    + " so waiting for a block would deadlock. Use runLater(block) instead");
        }
        final CompletableFuture<T> result = new CompletableFuture<>();
        executor.access(ui, () -> {
            try {
                result.complete(block.get());
            } catch (Throwable t) {
                result.completeExceptionally(t);
            }
        });
        return parkUnwrapped(result::get);
    }

    /**
     * Waits the strategy's way until the future a block is parked on is done.
     */
    @FunctionalInterface
    public interface Park<T> {
        /**
         * Waits until the future is done with the session lock released, and returns its value -
         * as {@link CompletableFuture#get()} does, which is what loom's park is.
         *
         * @apiNote Returns or throws with the session lock held again.
         */
        T park() throws InterruptedException, ExecutionException;
    }

    /**
     * Everything {@link BlockingExecutor#parkAndAwait} does but park: cancels {@code future} when
     * {@code anchor} dies, turns {@code park}'s checked exceptions into the {@code parkAndAwait}
     * contract, and on waking makes current the UI the anchor was last attached to - which follows a
     * {@code @PreserveOnRefresh} migration, and survives a dialog removed once answered.
     * <pre>{@code
     * return StrategySupport.awaitAnchored(anchor, future, future::get);
     * }</pre>
     *
     * @param park waits for {@code future}.
     * @return what {@code park} returned.
     * @throws CancellationException if {@code future} is cancelled, or {@code park} is interrupted.
     * @throws IllegalStateException if there is no current UI.
     */
    public static <T> T awaitAnchored(@NotNull Component anchor, @NotNull CompletableFuture<T> future, @NotNull Park<T> park) {
        final UI ui = UI.getCurrent();
        if (ui == null || ui.getSession() == null) {
            throw new IllegalStateException("No UI.getCurrent(): a block always has one");
        }
        final AnchorWatch watch = new AnchorWatch(ui.getSession(), anchor.getUI().orElse(ui), future);
        final Registration onAttach = anchor.addAttachListener(watch::attached);
        final Registration onDetach = anchor.addDetachListener(watch::detached);
        try {
            return parkUnwrapped(park);
        } finally {
            onAttach.remove();
            onDetach.remove();
            if (watch.ui != null) {
                CurrentInstance.setCurrent(watch.ui);
            }
        }
    }

    /**
     * Follows an anchor while a block waits on it, and cancels the future once the anchor stays
     * detached. The verdict waits for the request that detached it: in a {@code @PreserveOnRefresh}
     * navigation the view is detached from the old UI, the access queue drains inside
     * {@code prevUi.close()}, and only then is the view attached to the new UI. So a check queued on
     * detach that finds the anchor detached looks once more just before the response of the UI that
     * detached it; with that UI closing - a session destroy, a tab close - it cancels at once.
     * <p>
     * Transient fields: a parked block never survives session serialization, and neither the
     * future nor a UI belong in the serialized component tree.
     */
    private static final class AnchorWatch implements Serializable {
        /**
         * Captured up front: on session destroy a UI loses its session before its tree detaches.
         */
        @Nullable
        private final transient VaadinSession session;
        /**
         * The UI the anchor was last attached to.
         */
        @Nullable
        private transient UI ui;
        @Nullable
        private final transient CompletableFuture<?> future;

        AnchorWatch(@NotNull VaadinSession session, @NotNull UI ui, @NotNull CompletableFuture<?> future) {
            this.session = session;
            this.ui = ui;
            this.future = Objects.requireNonNull(future);
        }

        void attached(@NotNull AttachEvent event) {
            ui = event.getUI();
        }

        void detached(@NotNull DetachEvent event) {
            if (session == null || future == null) {
                return; // deserialized: the wait is gone already
            }
            final Component anchor = event.getSource();
            final UI detachingUI = UI.getCurrent();
            session.access(() -> {
                if (anchor.isAttached()) {
                    return;
                }
                if (detachingUI == null || detachingUI.isClosing() || detachingUI.getSession() == null) {
                    future.cancel(false);
                } else {
                    detachingUI.beforeClientResponse(detachingUI, context -> {
                        if (!anchor.isAttached()) {
                            future.cancel(false);
                        }
                    });
                }
            });
        }
    }

    /**
     * {@code park} with the {@link BlockingExecutor#parkAndAwait} exception contract.
     */
    private static <T> T parkUnwrapped(@NotNull Park<T> park) {
        try {
            return park.park();
        } catch (ExecutionException e) {
            throw sneakyThrow(e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            final CancellationException cancelled = new CancellationException("The wait was interrupted");
            cancelled.initCause(e);
            throw cancelled;
        }
    }

    /**
     * Throws {@code t} as-is, checked or not; declared to return so that callers can {@code throw} it.
     */
    @SuppressWarnings("unchecked")
    @NotNull
    private static <E extends Throwable> RuntimeException sneakyThrow(@NotNull Throwable t) throws E {
        throw (E) t;
    }
}
