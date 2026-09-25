/*
 * Copyright 2026 Martin Vysny
 *
 * Licensed under the MIT License. See the LICENSE file in the project root
 * for the full license text.
 */
package com.github.mvysny.blockingdialogs;

import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.UI;
import org.jspecify.annotations.Nullable;

import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/**
 * The SPI a blocking strategy implements: it runs <i>UI fibers</i>, code on the UI thread that may
 * park until a future completes while the session lock is released, so the browser keeps receiving
 * updates and the answering click gets processed. App code calls {@link BlockingDialogs} instead.
 * <p>
 * A strategy registers its implementation in
 * {@code META-INF/services/com.github.mvysny.blockingdialogs.BlockingExecutor}, which is what
 * {@link #get()} finds - exactly one per app. A strategy implements {@link #runLater},
 * {@link #runUntilPark} and {@link #parkAndAwait} with {@link StrategySupport}, which holds the
 * strategy-neutral half:
 * <pre>{@code
 * public void runLater(Runnable body) {
 *     UI ui = StrategySupport.checkLockedUI();
 *     startUIFiberThread(() -> StrategySupport.runUIFiber(ui, body));  // the strategy's own part
 * }
 * public void runUntilPark(Runnable body) {
 *     UI ui = StrategySupport.checkLockedUI();
 *     if (StrategySupport.isInUIFiber()) {
 *         StrategySupport.runUIFiber(ui, body);  // inline
 *     } else {
 *         startUIFiberThreadAndAwaitItsPark(() -> StrategySupport.runUIFiber(ui, body));
 *     }
 * }
 * public <T extends @Nullable Object> T parkAndAwait(Component anchor, CompletableFuture<T> future) {
 *     checkInUIFiber();
 *     return StrategySupport.awaitAnchored(anchor, future, future::get);  // loom: get() unmounts
 * }
 * }</pre>
 * <p>
 * <b>Lifetime.</b> A UI fiber lives as long as the future it is parked on, and the future as long as
 * its <i>anchor</i>: the anchor's detach cancels it, unless the anchor is attached again by the time
 * the current request is handled ({@code @PreserveOnRefresh}). There is no executor scope: nothing
 * kills a parked UI fiber from outside.
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
    static BlockingExecutor get() {
        return StrategyLoader.get();
    }

    /**
     * Queues {@code body} to run as a new UI fiber, and returns at once. It starts after the calling
     * listener returns - or, called inside a UI fiber, once that UI fiber parks or ends. No other request
     * of the session runs between this call and the UI fiber's first park or end.
     *
     * @apiNote A {@link CancellationException} escaping the UI fiber - its wait died - ends it quietly;
     * anything else goes to the session's {@link com.vaadin.flow.server.ErrorHandler}.
     * <p>
     * Inside the UI fiber {@link UI#getCurrent()} and {@link com.vaadin.flow.server.VaadinSession#getCurrent()}
     * are set, but {@link com.vaadin.flow.server.VaadinRequest#getCurrent()} and
     * {@link com.vaadin.flow.server.VaadinResponse#getCurrent()} are {@code null} under every strategy:
     * capture request-scoped values before calling this. Thread-locals the UI fiber sets itself survive
     * its parks.
     * @throws IllegalStateException unless the calling thread holds the session lock with
     *                               {@link UI#getCurrent()} set - a listener, or code inside
     *                               {@link UI#access}. Background threads call {@link #access}.
     */
    void runLater(Runnable body);

    /**
     * Runs {@code body} as a new UI fiber, and returns once it parks or ends: the caller's next line
     * sees what the UI fiber did up to its first park. Called inside a UI fiber, runs it inline instead -
     * its parks park the calling UI fiber, and this returns once it ends. No other request of the
     * session runs before this returns.
     *
     * @apiNote Everything else is as for {@link #runLater}, the exceptions included: they go to the
     * {@link com.vaadin.flow.server.ErrorHandler}, never to the caller, inline too.
     * @throws IllegalStateException as {@link #runLater} does.
     */
    void runUntilPark(Runnable body);

    /**
     * {@link #runLater} for a background thread: {@code ui.access(() -> runLater(body))}.
     *
     * @throws com.vaadin.flow.component.UIDetachedException if {@code ui} is detached.
     */
    default void access(UI ui, Runnable body) {
        Objects.requireNonNull(body);
        ui.access(() -> runLater(body));
    }

    /**
     * Runs {@code body} and waits until it ends, parks included:
     * <ul>
     *     <li>inside a UI fiber, runs it inline - a park there is nested blocking;</li>
     *     <li>from a background thread not holding the session lock, runs it as a new UI fiber - a job
     *     asking the user mid-way.</li>
     * </ul>
     * Exceptions, {@link CancellationException} included, go to the caller, not the
     * {@link com.vaadin.flow.server.ErrorHandler} - as with {@link UI#accessSynchronously}.
     *
     * @return what {@code body} returned.
     * @throws IllegalStateException from any other thread holding the session lock, which would
     *                               deadlock; or inside a UI fiber, for a {@code ui} of another session.
     */
    default <T extends @Nullable Object> T accessSynchronously(UI ui, Supplier<T> body) {
        return StrategySupport.accessSynchronously(this, ui, body);
    }

    /**
     * Runs body in a UI fiber.
     * {@link #accessSynchronously(UI, Supplier)} for a UI fiber returning nothing.
     */
    default void accessSynchronously(UI ui, Runnable body) {
        Objects.requireNonNull(body);
        accessSynchronously(ui, () -> {
            body.run();
            return null;
        });
    }

    /**
     * Parks the calling UI fiber until {@code future} completes, the session lock released, and returns
     * its value on the caller's stack. Afterwards {@link UI#getCurrent()} is
     * the UI the anchor was last attached to.
     *
     * @param anchor whose life this wait belongs to - a dialog anchors its own answer. An anchor
     *               that is never attached never ends the wait.
     * @return the value {@code future} completed with. The cause of a failed {@code future} is
     * rethrown as-is, checked or not.
     * @throws CancellationException if {@code future} is cancelled - the anchor died - or the park is
     *                               interrupted, the interrupt flag restored. Let it escape: the UI fiber
     *                               ends quietly.
     * @throws IllegalStateException unless called inside a UI fiber.
     */
    <T extends @Nullable Object> T parkAndAwait(Component anchor, CompletableFuture<T> future);

    /**
     * Throws unless the calling thread may park: it runs a UI fiber.
     *
     * @throws IllegalStateException if it doesn't.
     */
    default void checkInUIFiber() {
        if (!StrategySupport.isInUIFiber()) {
            throw new IllegalStateException(Thread.currentThread()
                    + " runs no UI fiber, so it can't park. Wrap the code in BlockingDialogs.runLater(() -> ...)");
        }
    }
}
