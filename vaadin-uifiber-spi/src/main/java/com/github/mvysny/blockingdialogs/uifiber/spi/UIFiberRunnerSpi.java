/*
 * Copyright 2026 Martin Vysny
 *
 * Licensed under the MIT License. See the LICENSE file in the project root
 * for the full license text.
 */
package com.github.mvysny.blockingdialogs.uifiber.spi;

import com.vaadin.flow.server.VaadinSession;
import org.jetbrains.annotations.NotNull;

/**
 * The SPI a runner implements: it runs <i>UI fibers</i>, code of a Vaadin session that parks until
 * woken, the session lock released meanwhile so the waking click can get in. <b>No app calls it,
 * ever</b> - apps call {@code vaadin-blocking-dialogs}, which calls this:
 * <pre>{@code
 * runner.runUntilFirstPark(session, body);   // a listener; returns at body's first park()
 *
 * // inside body - a fiber waiting for a dialog:
 * Completable<Boolean> answer = runner.newCompletable(session);
 * okButton.addClickListener(e -> answer.complete(true));   // a later request, holding the lock
 * boolean ok = answer.park();                              // the lock released while it waits
 * }</pre>
 * Implementations drop the suffix: {@code LoomUIFiberRunner implements UIFiberRunnerSpi}.
 * <p>
 * <b>The lock.</b> A UI fiber holds the session lock whenever it runs, except inside
 * {@link Completable#park()}: every {@code park()} releases it, nothing else does. A fiber blocked
 * on IO, a bare {@code Future.get()} or {@code Thread.sleep()} keeps the lock, and the session waits
 * with it. The "first park" is thus the first {@code park()}, never an IO wait.
 * <p>
 * <b>Not the runner's job</b> - the API does it around these calls: the {@code body} it hands in
 * sets {@code UI.getCurrent()} and {@code VaadinSession.getCurrent()} itself, marks itself as a UI
 * fiber, and routes whatever it throws to the session's {@code ErrorHandler}. The API also watches
 * the anchor a fiber waits for, and runs a fiber started inside a fiber inline.
 * <p>
 * Thread-safe; one instance serves every session.
 */
public interface UIFiberRunnerSpi {
    /**
     * Runs {@code body} as a new UI fiber of {@code session}, and returns once it first parks or
     * ends. Called holding the lock of {@code session}, outside any UI fiber: from a listener, or
     * from an access task the session's unlock drains.
     * <p>
     * Until the return nothing reaches the browser and no other request of the session runs: the
     * caller's changes, the fiber's first segment's and the caller's after the return all travel in
     * one response. On return the caller holds the lock again, with the hold count it called with;
     * a fiber that parked continues, once woken, on the runner's own thread - never the caller's.
     *
     * @apiNote The thread the first segment runs on is not promised - the caller's under one runner,
     * a worker under another - so {@code body} can't rely on the caller's thread-locals.
     * @param session the fiber's session, whose lock it holds; not a UI, since the fiber follows its
     *                anchor to a new UI on a refresh.
     * @param body    the fiber's code; throws nothing.
     */
    void runUntilFirstPark(@NotNull VaadinSession session, @NotNull Runnable body);

    /**
     * A new one-shot wake-up for a UI fiber of {@code session} to park on. Called by that fiber.
     *
     * @param <R> the type of the value the fiber is woken with.
     */
    @NotNull
    <R> Completable<R> newCompletable(@NotNull VaadinSession session);
}
