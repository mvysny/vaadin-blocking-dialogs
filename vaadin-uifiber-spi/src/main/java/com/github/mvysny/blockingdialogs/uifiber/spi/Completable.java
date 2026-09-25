/*
 * Copyright 2026 Martin Vysny
 *
 * Licensed under the MIT License. See the LICENSE file in the project root
 * for the full license text.
 */
package com.github.mvysny.blockingdialogs.uifiber.spi;

import org.jspecify.annotations.Nullable;

import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;

/**
 * A one-shot wake-up a UI fiber parks on, made by {@link UIFiberRunnerSpi#newCompletable}; the
 * runner implements it, since {@link #park()} is where the session lock is released. The first
 * {@link #complete} or {@link #fail} wins, later calls do nothing; both are called holding the
 * session lock - the API routes a lock-less caller through {@code session.access}.
 * <p>
 * <b>The wake-up is an access task.</b> The woken fiber resumes at the session's next drain of
 * access tasks, before the lock is really released, and runs to its next {@link #park()} or end -
 * so before any UIDL goes out, and before any other request runs. A fiber it wakes in turn joins
 * the same drain, so a cascade settles in one go. As in Swing, where a modal's caller runs after the
 * OK listener returns and before the next event.
 *
 * @param <R> the type of the value the fiber is woken with.
 */
public interface Completable<R> {
    /**
     * Wakes the fiber with {@code value}, which {@link #park()} returns; may be {@code null}.
     */
    void complete(@Nullable R value);

    /**
     * Wakes the fiber with a failure. Called by the API only: when the anchor the fiber waits for
     * stays detached, and when the future the app waits for fails or is cancelled. A user's Cancel is
     * an answer, {@link #complete}, never this.
     *
     * @param cause a {@link CancellationException} when the wait is dead, which {@link #park()}
     *              throws as-is; any other cause when the wait failed, which it wraps in an
     *              {@link ExecutionException}.
     */
    void fail(Throwable cause);

    /**
     * Parks the calling UI fiber until {@link #complete} or {@link #fail}. Called at most once, by the
     * fiber that made this.
     * <p>
     * Releases every hold of the session lock while it waits, and takes them all again before it
     * returns or throws. Outside {@link UIFiberRunnerSpi#runUntilFirstPark} it first drains the
     * session's access tasks - a fiber this one woke, a {@code runLater} - and the fiber's changes so
     * far reach the browser by the time it waits (the app has {@code @Push}); inside, the caller's
     * unlock drains them and its response carries the changes. Already completed, it returns at once
     * and releases nothing - no park at all, for {@link UIFiberRunnerSpi#runUntilFirstPark} neither.
     *
     * @return the value passed to {@link #complete}.
     * @throws CancellationException if {@link #fail} got one: the wait is dead.
     * @throws ExecutionException    if {@link #fail} got any other cause, which it wraps.
     * @throws InterruptedException  if the fiber's thread was interrupted while waiting.
     */
    @Nullable
    R park() throws ExecutionException, InterruptedException;
}
