/*
 * Copyright 2026 Martin Vysny
 *
 * Licensed under the MIT License. See the LICENSE file in the project root
 * for the full license text.
 */
package com.github.mvysny.blockingdialogs.loom;

import com.github.mvysny.blockingdialogs.BlockingDialogs;
import com.github.mvysny.kaributesting.v10.MockVaadin;
import com.github.mvysny.kaributesting.v10.Routes;
import com.github.mvysny.kaributesting.v10.mock.MockedUI;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.server.VaadinSession;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Taking the Vaadin session lock from a UI fiber's virtual thread, which without
 * {@link VirtualThreadAwareLock} recurses until {@link StackOverflowError}: the virtual thread can
 * never win the lock its own carrier holds, and every release resubmits its continuation as an access
 * task, which re-locks and re-releases, forever (vaadin-loom#3).
 */
public class VirtualThreadAwareLockTest {
    private static Routes routes;

    private List<Throwable> reportedErrors;

    @BeforeAll
    public static void discoverRoutes() {
        routes = new Routes().autoDiscoverViews("com.github.mvysny.blockingdialogs.loom");
    }

    @BeforeEach
    public void setupVaadin() {
        MockVaadin.setup(MockedUI::new, new MockVirtualThreadAwareServlet(routes));
        reportedErrors = new ArrayList<>();
        VaadinSession.getCurrent().setErrorHandler(event -> reportedErrors.add(event.getThrowable()));
    }

    @AfterEach
    public void tearDownVaadin() {
        MockVaadin.tearDown();
    }

    @Test
    public void lockAndUnlockSession() {
        final VaadinSession session = VaadinSession.getCurrent();
        final AtomicBoolean gotTheLock = new AtomicBoolean();
        runInUIFiber(() -> {
            session.lock();
            gotTheLock.set(true);
            session.unlock();
        });
        assertTrue(gotTheLock.get(), "the virtual thread must be able to take the session lock");
        assertEquals(List.of(), reportedErrors);
    }

    @Test
    public void accessSynchronously() {
        final UI ui = UI.getCurrent();
        final AtomicBoolean ran = new AtomicBoolean();
        // accessSynchronously() calls lock() unconditionally - it never consults hasLock()
        runInUIFiber(() -> ui.accessSynchronously(() -> ran.set(true)));
        assertTrue(ran.get());
        assertEquals(List.of(), reportedErrors);
    }

    @Test
    public void hasLock() {
        final VaadinSession session = VaadinSession.getCurrent();
        final AtomicBoolean hasLock = new AtomicBoolean();
        runInUIFiber(() -> hasLock.set(session.hasLock()));
        assertTrue(hasLock.get(), "the carrier holds the session lock, so the virtual thread effectively does too");
    }

    @Test
    public void unlockingMoreThanLockingFails() {
        final VaadinSession session = VaadinSession.getCurrent();
        runInUIFiber(session::unlock);
        assertEquals(1, reportedErrors.size(), "expected exactly one error, got " + reportedErrors);
        final Throwable error = reportedErrors.get(0);
        assertInstanceOf(IllegalStateException.class, error);
        assertEquals("The Vaadin session lock can't be fully unlocked from a UI virtual thread", error.getMessage());
    }

    /**
     * The pretense must not leak: while the virtual thread is mounted, its carrier really does hold
     * the lock, and everybody else still has to wait for it.
     */
    @Test
    public void otherThreadsBlockWhileTheVirtualThreadIsMounted() {
        final VaadinSession session = VaadinSession.getCurrent();
        final AtomicReference<Boolean> acquired = new AtomicReference<>();

        runInUIFiber(() -> {
            Thread.ofPlatform().start(() -> {
                final boolean got = session.getLockInstance().tryLock();
                if (got) {
                    session.getLockInstance().unlock();
                }
                acquired.set(got);
            });
            // Spin rather than join(): join() would unmount this virtual thread, the carrier would
            // release the session lock, and the other thread would get it - the very thing under test.
            final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (acquired.get() == null && System.nanoTime() < deadline) {
                Thread.onSpinWait();
            }
        });

        assertNotNull(acquired.get(), "the platform thread never reported back");
        assertFalse(acquired.get(), "the carrier holds the session lock, so nobody else may have it");
        assertEquals(List.of(), reportedErrors);
    }

    /**
     * Pretend mode is gated on the marker a UI fiber sets, not on {@link Thread#isVirtual()} and not on
     * {@code VaadinSession.getCurrent() == this} - either of those would hand a no-op lock to any
     * background virtual thread that happens to have a current session, and the session would
     * silently stop being protected.
     */
    @Test
    public void backgroundVirtualThreadGetsTheRealLock() throws InterruptedException {
        final VaadinSession session = VaadinSession.getCurrent();
        final AtomicReference<Boolean> hasLock = new AtomicReference<>();
        final AtomicReference<Boolean> acquired = new AtomicReference<>();

        // this test thread holds the session lock throughout
        final Thread thread = Thread.ofVirtual().start(() -> {
            VaadinSession.setCurrent(session);
            hasLock.set(session.hasLock());
            final boolean got = session.getLockInstance().tryLock();
            if (got) {
                session.getLockInstance().unlock();
            }
            acquired.set(got);
        });
        thread.join();

        assertEquals(Boolean.FALSE, hasLock.get());
        assertEquals(Boolean.FALSE, acquired.get());
    }

    /**
     * A virtual thread started inside a UI fiber inherits the UI fiber's scheduler but carries no marker;
     * {@link LoomUtils#newVirtualThread} carries it off the UI thread, so it takes the session lock
     * like any background thread: it blocks until the UI thread releases the lock, rather than
     * recursing.
     */
    @Test
    public void virtualThreadStartedInsideAUIFiberTakesTheRealLock() throws InterruptedException {
        final VaadinSession session = VaadinSession.getCurrent();
        final AtomicReference<Thread> child = new AtomicReference<>();
        final AtomicBoolean gotTheLock = new AtomicBoolean();
        BlockingDialogs.runLater(() -> child.set(Thread.ofVirtual().start(() -> {
            session.lock();
            try {
                gotTheLock.set(session.hasLock());
            } finally {
                session.unlock();
            }
        })));
        MockVaadin.clientRoundtrip(true);
        // this test thread holds the session lock again, so the child needs a gap to take it in
        session.unlock();
        try {
            assertTrue(child.get().join(Duration.ofSeconds(5)), "the child never got the session lock");
        } finally {
            session.lock();
        }
        assertTrue(gotTheLock.get(), "the child must have held the real session lock");
        assertEquals(List.of(), reportedErrors);
    }

    /**
     * A UI fiber that loses its marker is back to spinning on the session lock its own carrier holds.
     * The depth guard in the carrier must cut that short instead of letting it reach
     * {@link StackOverflowError}.
     */
    @Test
    public void runawayContinuationIsRejectedRatherThanOverflowingTheStack() {
        final VaadinSession session = VaadinSession.getCurrent();
        BlockingDialogs.runLater(() -> {
            VirtualThreadAwareLock.exitUIVirtualThread();
            session.lock();
        });

        // Not MockVaadin.clientRoundtrip(): its finally block replaces whatever unlock() throws
        // with an assertion about the session lock. unlock() is what drains the UI queue anyway.
        final RejectedExecutionException ex = assertThrows(RejectedExecutionException.class, session::unlock);
        assertTrue(ex.getMessage().contains("deep on"), ex.getMessage());

        // Without the guard this is what the developer gets instead: FutureTask captures the
        // StackOverflowError off the continuation and the session error handler reports it, ~1000
        // frames of the same eight-frame cycle and no hint of the cause.
        assertEquals(List.of(), reportedErrors);

        // The session survived: the runaway was cut before it could take the stack with it. The
        // UI fiber stays stranded, but a stranded virtual thread is never resubmitted, so it can't
        // restart the runaway either.
        assertTrue(session.getLockInstance().tryLock(), "the session lock must still be usable");
    }

    /**
     * Runs {@code body} as a UI fiber and drains the UI queue. Any failure is routed to the session
     * error handler, so assert on {@link #reportedErrors} rather than expecting a throw.
     */
    private void runInUIFiber(Runnable body) {
        final AtomicBoolean finished = new AtomicBoolean();
        BlockingDialogs.runLater(() -> {
            try {
                body.run();
            } finally {
                finished.set(true);
            }
        });
        // `true` keeps our own error handler instead of Karibu's fail-the-test one
        MockVaadin.clientRoundtrip(true);
        assertTrue(finished.get(), "the UI fiber never ran to completion - did it unmount?");
    }
}
