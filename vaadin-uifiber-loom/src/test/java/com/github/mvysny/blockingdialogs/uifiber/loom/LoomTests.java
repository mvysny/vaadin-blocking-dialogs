/*
 * Copyright 2026 Martin Vysny
 *
 * Licensed under the Apache License, Version 2.0. See the LICENSE file in the
 * project root for the full license text.
 */
package com.github.mvysny.blockingdialogs.uifiber.loom;

import com.github.mvysny.kaributesting.v10.MockVaadin;
import com.github.mvysny.kaributesting.v10.Routes;
import com.github.mvysny.kaributesting.v10.mock.MockedUI;
import com.vaadin.flow.server.VaadinSession;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Karibu on the loom runner, and the session-lock choreography the tests play against it:
 * <pre>{@code
 * reportedErrors = LoomTests.setupVaadin();
 * final Thread request = LoomTests.queueARequest(() -> log.add("request"));
 * LoomTests.withSessionLockReleased(() -> request.join());   // the request runs now
 * }</pre>
 */
final class LoomTests {
    private LoomTests() {
    }

    static final Routes ROUTES = new Routes().autoDiscoverViews("com.github.mvysny.blockingdialogs.uifiber.loom");

    /**
     * Sets up Karibu with the session lock the loom runner needs.
     *
     * @return what the session's error handler gets from now on
     */
    static List<Throwable> setupVaadin() {
        MockVaadin.setup(MockedUI::new, new MockVirtualThreadAwareServlet(ROUTES));
        final List<Throwable> reportedErrors = new CopyOnWriteArrayList<>();
        VaadinSession.getCurrent().setErrorHandler(event -> reportedErrors.add(event.getThrowable()));
        return reportedErrors;
    }

    /**
     * Starts a platform thread that runs {@code body} holding the current session's lock, as a
     * request does; returns once that thread queues on the lock, which somebody else holds.
     */
    static Thread queueARequest(Runnable body) {
        final VaadinSession session = Objects.requireNonNull(VaadinSession.getCurrent());
        final Thread request = Thread.ofPlatform().start(() -> {
            session.lock();
            try {
                body.run();
            } finally {
                session.unlock();
            }
        });
        final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (request.getState() != Thread.State.WAITING && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
        assertEquals(Thread.State.WAITING, request.getState(), "the request queued on the session lock");
        return request;
    }

    /**
     * Runs {@code body} with the test thread's hold on the current session's lock let go, so that
     * other threads can take it; takes it back afterwards.
     */
    static <E extends Exception> void withSessionLockReleased(Body<E> body) throws E {
        final VaadinSession session = Objects.requireNonNull(VaadinSession.getCurrent());
        session.unlock();
        try {
            body.run();
        } finally {
            session.lock();
        }
    }

    @FunctionalInterface
    interface Body<E extends Exception> {
        void run() throws E;
    }
}
