/*
 * Copyright 2026 Martin Vysny
 *
 * Licensed under the MIT License. See the LICENSE file in the project root
 * for the full license text.
 */
package com.github.mvysny.blockingdialogs;

import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.UI;
import org.jetbrains.annotations.NotNull;

import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/**
 * The SPI a blocking strategy implements: it runs <i>blocks</i>, code on the UI thread that may
 * park until a future completes while the session lock is released, so the browser keeps receiving
 * updates and the answering click gets processed. App code calls {@link BlockingDialogs} instead.
 * <p>
 * A strategy registers its implementation in
 * {@code META-INF/services/com.github.mvysny.blockingdialogs.BlockingExecutor}, which is what
 * {@link #get()} finds - exactly one per app. A strategy implements {@link #runLater},
 * {@link #runUntilPark} and {@link #parkAndAwait} with {@link StrategySupport}, which holds the
 * strategy-neutral half:
 * <pre>{@code
 * public void runLater(Runnable block) {
 *     UI ui = StrategySupport.checkLockedUI();
 *     startBlockThread(() -> StrategySupport.runBlock(ui, block));  // the strategy's own part
 * }
 * public void runUntilPark(Runnable block) {
 *     UI ui = StrategySupport.checkLockedUI();
 *     if (StrategySupport.isInBlock()) {
 *         StrategySupport.runBlock(ui, block);  // inline
 *     } else {
 *         startBlockThreadAndAwaitItsPark(() -> StrategySupport.runBlock(ui, block));
 *     }
 * }
 * public <T> T parkAndAwait(Component anchor, CompletableFuture<T> future) {
 *     checkUIThreadWithBlockingCapabilities();
 *     return StrategySupport.awaitAnchored(anchor, future, future::get);  // loom: get() unmounts
 * }
 * }</pre>
 * <p>
 * <b>Lifetime.</b> A block lives as long as the future it is parked on, and the future as long as
 * its <i>anchor</i>: the anchor's detach cancels it, unless the anchor is attached again by the time
 * the current request is handled ({@code @PreserveOnRefresh}). There is no executor scope: nothing
 * kills a parked block from outside.
 * <p>
 * Thread-safe, and stateless app-wide.
 */
public interface BlockingExecutor {
    /**
     * The strategy on the classpath, found through {@link java.util.ServiceLoader} once per
     * classloader.
     *
     * @throws IllegalStateException if there is none, or more than one - on every call alike.
     */
    @NotNull
    static BlockingExecutor get() {
        return StrategyLoader.get();
    }

    /**
     * Queues {@code block} to run as a new block, and returns at once. It starts after the calling
     * listener returns - or, called inside a block, once that block parks or ends. No other request
     * of the session runs between this call and the block's first park or end.
     *
     * @apiNote A {@link CancellationException} escaping the block - its wait died - ends it quietly;
     * anything else goes to the session's {@link com.vaadin.flow.server.ErrorHandler}.
     * <p>
     * Inside the block {@link UI#getCurrent()} and {@link com.vaadin.flow.server.VaadinSession#getCurrent()}
     * are set, but {@link com.vaadin.flow.server.VaadinRequest#getCurrent()} and
     * {@link com.vaadin.flow.server.VaadinResponse#getCurrent()} are {@code null} under every strategy:
     * capture request-scoped values before calling this. Thread-locals the block sets itself survive
     * its parks.
     * @throws IllegalStateException unless the calling thread holds the session lock with
     *                               {@link UI#getCurrent()} set - a listener, or code inside
     *                               {@link UI#access}. Background threads call {@link #access}.
     */
    void runLater(@NotNull Runnable block);

    /**
     * Runs {@code block} as a new block, and returns once it parks or ends: the caller's next line
     * sees what the block did up to its first park. Called inside a block, runs it inline instead -
     * its parks park the calling block, and this returns once it ends. No other request of the
     * session runs before this returns.
     *
     * @apiNote Everything else is as for {@link #runLater}, the exceptions included: they go to the
     * {@link com.vaadin.flow.server.ErrorHandler}, never to the caller, inline too.
     * @throws IllegalStateException as {@link #runLater} does.
     */
    void runUntilPark(@NotNull Runnable block);

    /**
     * {@link #runLater} for a background thread: {@code ui.access(() -> runLater(block))}.
     *
     * @throws com.vaadin.flow.component.UIDetachedException if {@code ui} is detached.
     */
    default void access(@NotNull UI ui, @NotNull Runnable block) {
        Objects.requireNonNull(block);
        ui.access(() -> runLater(block));
    }

    /**
     * Runs {@code block} and waits until it ends, parks included:
     * <ul>
     *     <li>inside a block, runs it inline - a park there is nested blocking;</li>
     *     <li>from a background thread not holding the session lock, runs it as a new block - a job
     *     asking the user mid-way.</li>
     * </ul>
     * Exceptions, {@link CancellationException} included, go to the caller, not the
     * {@link com.vaadin.flow.server.ErrorHandler} - as with {@link UI#accessSynchronously}.
     *
     * @return what {@code block} returned.
     * @throws IllegalStateException from any other thread holding the session lock, which would
     *                               deadlock; or inside a block, for a {@code ui} of another session.
     */
    default <T> T accessSynchronously(@NotNull UI ui, @NotNull Supplier<T> block) {
        return StrategySupport.accessSynchronously(this, ui, block);
    }

    /**
     * {@link #accessSynchronously(UI, Supplier)} for a block returning nothing.
     */
    default void accessSynchronously(@NotNull UI ui, @NotNull Runnable block) {
        Objects.requireNonNull(block);
        accessSynchronously(ui, () -> {
            block.run();
            return null;
        });
    }

    /**
     * Parks the calling block until {@code future} completes, the session lock released, and returns
     * its value on the caller's stack. Afterwards {@link UI#getCurrent()} is
     * the UI the anchor was last attached to.
     *
     * @param anchor whose life this wait belongs to - a dialog anchors its own answer. An anchor
     *               that is never attached never ends the wait.
     * @return the value {@code future} completed with. The cause of a failed {@code future} is
     * rethrown as-is, checked or not.
     * @throws CancellationException if {@code future} is cancelled - the anchor died - or the park is
     *                               interrupted, the interrupt flag restored. Let it escape: the block
     *                               ends quietly.
     * @throws IllegalStateException unless called inside a block.
     */
    <T> T parkAndAwait(@NotNull Component anchor, @NotNull CompletableFuture<T> future);

    /**
     * Throws unless the calling thread may park: it runs a block.
     *
     * @throws IllegalStateException if it doesn't.
     */
    default void checkUIThreadWithBlockingCapabilities() {
        if (!StrategySupport.isInBlock()) {
            throw new IllegalStateException(Thread.currentThread()
                    + " runs no block, so it can't park. Wrap the code in BlockingDialogs.runLater(() -> ...)");
        }
    }
}
