/*
 * Copyright 2026 Martin Vysny
 *
 * Licensed under the Apache License, Version 2.0. See the LICENSE file in the
 * project root for the full license text.
 */
package com.github.mvysny.blockingdialogs.uifiber.loom;

import com.github.mvysny.blockingdialogs.UIFibers;
import com.github.mvysny.blockingdialogs.uifiber.spi.Completable;
import com.github.mvysny.blockingdialogs.uifiber.spi.UIFiberRunnerSpi;
import com.github.mvysny.kaributesting.v10.MockVaadin;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.server.VaadinSession;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledForJreRange;

import java.util.ArrayList;
import java.util.List;
import java.util.ServiceLoader;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The loom runner under Karibu. The test thread plays the carrier: it holds the session lock, and
 * each roundtrip drains the access queue, mounting whichever UI fibers have a continuation queued.
 * {@code Contract} calls the runner directly, as the API does; the rest goes through the API, as an
 * app does.
 */
public class LoomUIFiberRunnerTest {
    private final LoomUIFiberRunner runner = new LoomUIFiberRunner();

    /**
     * What the UI fibers of a test did, in order.
     */
    private final List<String> log = new CopyOnWriteArrayList<>();

    /**
     * Captured on the test thread: a bare SPI body has no current instances, the API sets them.
     */
    private VaadinSession session;

    /**
     * Everything handed to the session's error handler.
     */
    private List<Throwable> reportedErrors;

    /**
     * What escaped a {@link #runUntilFirstPark} body: the SPI's bodies throw nothing.
     */
    private final List<Throwable> bodyFailures = new CopyOnWriteArrayList<>();

    @BeforeEach
    public void setupVaadin() {
        reportedErrors = LoomTests.setupVaadin();
        session = VaadinSession.getCurrent();
    }

    @AfterEach
    public void tearDownVaadin() {
        MockVaadin.tearDown();
        assertEquals(List.of(), bodyFailures);
    }

    @Nested
    public class Wiring {
        @Test
        public void isTheRunnerOnTheClasspath() {
            final List<UIFiberRunnerSpi> found = new ArrayList<>();
            ServiceLoader.load(UIFiberRunnerSpi.class).forEach(found::add);
            assertEquals(List.of(LoomUIFiberRunner.class), found.stream().map(Object::getClass).toList());
        }

        /**
         * {@link SessionLockCheck}: the session's first request fails, before any UI fiber. An
         * {@link Error}, since Karibu rethrows the {@code Exception} that real Vaadin only logs.
         */
        @Test
        public void refusesAnUnwrappedSessionLockAtSessionInit() {
            MockVaadin.tearDown();
            final Throwable e = assertThrows(Throwable.class, () -> MockVaadin.setup(LoomTests.ROUTES));
            final StringBuilder messages = new StringBuilder();
            boolean isError = false;
            for (Throwable t = e; t != null; t = t.getCause()) {
                messages.append(t.getMessage()).append('\n');
                isError |= t.getClass() == Error.class;
            }
            assertTrue(messages.toString().contains("LoomVaadinServlet"), messages.toString());
            assertTrue(isError, messages.toString());
        }

        @Test
        public void refusesAPinningJdkUnlessAllowed() {
            final IllegalStateException e = assertThrows(IllegalStateException.class, () -> LoomUIFiberRunner.checkJdk(21, false));
            assertTrue(e.getMessage().contains("JEP 491"), e.getMessage());
            assertTrue(e.getMessage().contains("-D" + LoomUIFiberRunner.ALLOW_PINNING_JDK + "=true"), e.getMessage());
            LoomUIFiberRunner.checkJdk(21, true);
            LoomUIFiberRunner.checkJdk(24, false);
        }
    }

    /**
     * What {@link UIFiberRunnerSpi}'s and {@link Completable}'s javadoc promise, the runner called
     * directly.
     */
    @Nested
    public class Contract {
        @Test
        public void returnsAtTheFirstParkAndResumesOnceCompleted() {
            final AtomicReference<Completable<String>> answer = new AtomicReference<>();
            runUntilFirstPark(() -> {
                log.add("segment");
                answer.set(runner.newCompletable(session));
                log.add("resumed: " + answer.get().park());
            });
            assertEquals(List.of("segment"), log);
            answer.get().complete("yes");
            MockVaadin.clientRoundtrip();
            assertEquals(List.of("segment", "resumed: yes"), log);
        }

        @Test
        public void aCompletedCompletableReturnsWithoutParking() {
            runUntilFirstPark(() -> {
                final Completable<String> answer = runner.newCompletable(session);
                answer.complete("done");
                log.add(answer.park());
            });
            assertEquals(List.of("done"), log);
        }

        @Test
        public void theFirstWakeUpWins() {
            runUntilFirstPark(() -> {
                final Completable<String> answer = runner.newCompletable(session);
                answer.complete("first");
                answer.complete("second");
                answer.fail(new RuntimeException("third"));
                log.add(answer.park());
            });
            assertEquals(List.of("first"), log);
        }

        @Test
        public void failWithACancellationThrowsItAsIs() {
            final CancellationException dead = new CancellationException("the wait is dead");
            final AtomicReference<Throwable> caught = new AtomicReference<>();
            runUntilFirstPark(() -> {
                final Completable<String> answer = runner.newCompletable(session);
                answer.fail(dead);
                try {
                    answer.park();
                } catch (CancellationException e) {
                    caught.set(e);
                }
            });
            assertSame(dead, caught.get());
        }

        @Test
        public void failWithAnyOtherCauseWrapsIt() {
            final RuntimeException boom = new RuntimeException("boom");
            final AtomicReference<Throwable> caught = new AtomicReference<>();
            runUntilFirstPark(() -> {
                final Completable<String> answer = runner.newCompletable(session);
                answer.fail(boom);
                try {
                    answer.park();
                } catch (ExecutionException e) {
                    caught.set(e.getCause());
                }
            });
            assertSame(boom, caught.get());
        }

        @Test
        public void aUIFiberHoldsTheLockAndKeepsItsThreadAcrossAPark() {
            final AtomicReference<Completable<String>> answer = new AtomicReference<>();
            final List<Object> seen = new CopyOnWriteArrayList<>();
            runUntilFirstPark(() -> {
                seen.add(Thread.currentThread());
                seen.add(session.hasLock());
                answer.set(runner.newCompletable(session));
                answer.get().park();
                seen.add(Thread.currentThread());
                seen.add(session.hasLock());
            });
            answer.get().complete("x");
            MockVaadin.clientRoundtrip();
            assertEquals(4, seen.size());
            assertTrue(((Thread) seen.get(0)).isVirtual());
            assertSame(seen.get(0), seen.get(2));
            assertEquals(List.of(true, true), List.of(seen.get(1), seen.get(3)));
        }

        @Test
        public void runUntilFirstParkRefusesACallerWithoutTheLock() throws InterruptedException {
            final AtomicReference<Throwable> thrown = new AtomicReference<>();
            final Thread thread = Thread.ofPlatform().start(() -> {
                try {
                    runner.runUntilFirstPark(session, () -> log.add("ran"));
                } catch (Throwable t) {
                    thrown.set(t);
                }
            });
            thread.join();
            assertInstanceOf(IllegalStateException.class, thrown.get());
            assertEquals(List.of(), log);
        }

        @Test
        public void runUntilFirstParkRefusesACallerInsideAUIFiber() {
            final AtomicReference<Throwable> thrown = new AtomicReference<>();
            runUntilFirstPark(() -> {
                try {
                    runner.runUntilFirstPark(session, () -> log.add("ran"));
                } catch (IllegalStateException e) {
                    thrown.set(e);
                }
            });
            assertNotNull(thrown.get());
            assertEquals(List.of(), log);
        }

        @Test
        public void newCompletableRefusesACallerOutsideAUIFiber() {
            assertThrows(IllegalStateException.class, () -> runner.newCompletable(session));
        }

        @Test
        public void parkRefusesAnotherThread() {
            final AtomicReference<Completable<String>> answer = new AtomicReference<>();
            runUntilFirstPark(() -> answer.set(runner.newCompletable(session)));
            assertThrows(IllegalStateException.class, () -> answer.get().park());
        }

        @Test
        public void parkRefusesASecondPark() {
            final AtomicReference<Throwable> thrown = new AtomicReference<>();
            runUntilFirstPark(() -> {
                final Completable<String> answer = runner.newCompletable(session);
                answer.complete("x");
                answer.park();
                try {
                    answer.park();
                } catch (IllegalStateException e) {
                    thrown.set(e);
                }
            });
            assertNotNull(thrown.get());
        }
    }

    @Nested
    public class VirtualThreads {
        @Test
        public void aUIFiberRunsInAVirtualThreadOfItsOwn() {
            final UI ui = UI.getCurrent();
            final AtomicReference<Thread> thread = new AtomicReference<>();
            UIFibers.runUntilPark(() -> thread.set(Thread.currentThread()));
            assertTrue(thread.get().isVirtual());
            assertSame(ui, UI.getCurrent(), "the UI fiber's current instances are its own thread's");
        }

        /**
         * On JDK 21-23 the virtual thread pins and parks its carrier instead - the test thread,
         * holding the session lock: {@code clientRoundtrip()} would never return, hence the gate. See
         * {@code R_vt_pinning}.
         */
        @Test
        @EnabledForJreRange(minVersion = 24, disabledReason = "Parking inside synchronized pins the carrier before JEP 491 (JDK 24)")
        public void parkingInsideSynchronizedUnmounts() {
            final Object monitor = new Object();
            final CompletableFuture<String> answer = new CompletableFuture<>();
            UIFibers.runLater(() -> {
                synchronized (monitor) {
                    log.add(UIFibers.parkAndAwait(UI.getCurrent(), answer));
                }
            });
            MockVaadin.clientRoundtrip();
            assertEquals(List.of(), log, "the UI fiber must be parked inside the synchronized block");

            answer.complete("resumed");
            MockVaadin.clientRoundtrip();
            assertEquals(List.of("resumed"), log);
            assertEquals(List.of(), reportedErrors);
        }

        /**
         * The first segment mounts on the caller rather than as an access task.
         */
        @Test
        public void runUntilParkRunsNoAccessTaskQueuedEarlier() {
            UI.getCurrent().access(() -> log.add("earlier task"));
            UIFibers.runUntilPark(() -> log.add("UI fiber"));
            assertEquals(List.of("UI fiber"), log);
            MockVaadin.clientRoundtrip();
            assertEquals(List.of("UI fiber", "earlier task"), log);
        }

        /**
         * A virtual caller can't carry the UI fiber, so a platform thread does while the caller waits.
         */
        @Test
        public void runUntilParkRunsOnAVirtualThreadOutsideAUIFiber() throws InterruptedException {
            final UI ui = UI.getCurrent();
            final AtomicReference<Throwable> thrown = new AtomicReference<>();
            LoomTests.withSessionLockReleased(() ->
                    Thread.ofVirtual().start(() -> ui.accessSynchronously(() -> {
                        try {
                            UIFibers.runUntilPark(() -> log.add("UI fiber"));
                            log.add("returned");
                        } catch (Throwable t) {
                            thrown.set(t);
                        }
                    })).join());
            assertNull(thrown.get());
            assertEquals(List.of("UI fiber", "returned"), log);
        }
    }

    /**
     * The thread that completes the future is the one that queues the UI fiber's next continuation.
     */
    @Nested
    public class Resuming {
        @Test
        public void byAnotherUIFiber() {
            final CompletableFuture<String> answer = new CompletableFuture<>();
            UIFibers.runLater(() -> log.add(UIFibers.parkAndAwait(UI.getCurrent(), answer)));
            MockVaadin.clientRoundtrip();
            UIFibers.runLater(() -> answer.complete("hello"));
            MockVaadin.clientRoundtrip();
            MockVaadin.clientRoundtrip();
            assertEquals(List.of("hello"), log);
            assertEquals(List.of(), reportedErrors);
        }

        /**
         * Anything handed out by {@code Executors.newVirtualThreadPerTaskExecutor()}; the carrier
         * mustn't reject a virtual caller.
         */
        @Test
        public void byABackgroundVirtualThreadWhileTheLockIsHeld() throws InterruptedException {
            final CompletableFuture<String> answer = new CompletableFuture<>();
            UIFibers.runLater(() -> log.add(UIFibers.parkAndAwait(UI.getCurrent(), answer)));
            MockVaadin.clientRoundtrip();
            Thread.ofVirtual().start(() -> answer.complete("hello")).join();
            MockVaadin.clientRoundtrip();
            assertEquals(List.of("hello"), log);
            assertEquals(List.of(), reportedErrors);
        }

        /**
         * With the lock free, the completing virtual thread drains the access queue itself, and a
         * continuation can't mount on it: it goes to a platform thread instead.
         */
        @Test
        public void byABackgroundVirtualThreadWhileTheLockIsFree() throws Exception {
            final CompletableFuture<String> answer = new CompletableFuture<>();
            final CompletableFuture<String> resumed = new CompletableFuture<>();
            UIFibers.runLater(() -> resumed.complete(UIFibers.parkAndAwait(UI.getCurrent(), answer)));
            MockVaadin.clientRoundtrip();

            LoomTests.withSessionLockReleased(() -> {
                Thread.ofVirtual().start(() -> answer.complete("hello")).join();
                assertEquals("hello", resumed.get(5, TimeUnit.SECONDS));
            });
            assertEquals(List.of(), reportedErrors);
        }
    }

    /**
     * {@link LoomUIFiberRunner#runUntilFirstPark} on the current session, collecting what escapes
     * {@code body} into {@link #bodyFailures}.
     */
    private void runUntilFirstPark(Body body) {
        runner.runUntilFirstPark(session, () -> {
            try {
                body.run();
            } catch (Throwable t) {
                bodyFailures.add(t);
            }
        });
    }

    @FunctionalInterface
    private interface Body {
        void run() throws Exception;
    }
}
