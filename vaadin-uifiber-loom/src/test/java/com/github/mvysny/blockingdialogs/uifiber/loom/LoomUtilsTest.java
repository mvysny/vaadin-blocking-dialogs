/*
 * Copyright 2026 Martin Vysny
 *
 * Licensed under the MIT License. See the LICENSE file in the project root
 * for the full license text.
 */
package com.github.mvysny.blockingdialogs.uifiber.loom;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The reflection hack, and which continuations reach a UI fiber's carrier. JVM-implementation-sensitive
 * (JDK-8308541), which is why CI runs them on every JDK vendor. The carrier here is one platform
 * thread, which reproduces the property that matters: a continuation handed to it cannot run while
 * another one is mounted.
 */
public class LoomUtilsTest {
    private RecordingCarrier carrier;

    @BeforeEach
    public void setUp() {
        carrier = new RecordingCarrier();
    }

    @AfterEach
    public void tearDown() {
        carrier.close();
    }

    @Test
    public void newVirtualBuilderRunsContinuationsOnGivenExecutor() {
        final AtomicInteger continuationsRun = new AtomicInteger();
        final AtomicReference<Thread> carrierThread = new AtomicReference<>();
        final Executor synchronousExecutor = command -> {
            continuationsRun.incrementAndGet();
            carrierThread.set(Thread.currentThread());
            command.run();
        };

        final AtomicBoolean ranInVirtualThread = new AtomicBoolean();
        final Thread thread = LoomUtils.newVirtualBuilder(synchronousExecutor)
                .name("test-vt")
                .unstarted(() -> ranInVirtualThread.set(Thread.currentThread().isVirtual()));
        assertTrue(thread.isVirtual());
        thread.start();

        assertEquals(1, continuationsRun.get(), "the continuation must have been run by our executor");
        // our executor runs the continuation inline, therefore the carrier is this very test thread
        assertSame(Thread.currentThread(), carrierThread.get());
        assertTrue(ranInVirtualThread.get());
    }

    @Test
    public void threadOfVirtualInsideAUIFiberRunsWhileTheUIFiberIsMounted() throws InterruptedException {
        assertStartedThreadRunsBesideTheUIFiber(task -> Thread.ofVirtual().start(task));
    }

    /**
     * Looks like it asks for the default scheduler, and inherits just the same.
     */
    @Test
    public void virtualThreadPerTaskExecutorInsideAUIFiberRunsWhileTheUIFiberIsMounted() throws InterruptedException {
        assertStartedThreadRunsBesideTheUIFiber(task -> {
            final ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();
            pool.submit(task);
            pool.shutdown();
        });
    }

    /**
     * The grandchild inherits the scheduler from the child, so it must be recognised just the same.
     */
    @Test
    public void aThreadStartedByAnInheritedThreadRunsOffTheCarrierToo() throws InterruptedException {
        assertStartedThreadRunsBesideTheUIFiber(task -> Thread.ofVirtual().start(() -> Thread.ofVirtual().start(task)));
    }

    /**
     * The benign case: a UI fiber resuming after a park still resumes through its carrier.
     */
    @Test
    public void aUIFiberUnparkedByAnotherUIFiberResumesThroughTheCarrier() throws InterruptedException {
        final CountDownLatch release = new CountDownLatch(1);
        final CountDownLatch resumed = new CountDownLatch(1);
        final Thread parked = LoomUtils.newVirtualThread(carrier, "test-ui-fiber-parked", () -> {
            await(release);
            resumed.countDown();
        });
        parked.start();
        awaitWaiting(parked);

        LoomUtils.newVirtualThread(carrier, "test-ui-fiber-releasing", release::countDown).start();

        assertTrue(resumed.await(5, TimeUnit.SECONDS), "the parked UI fiber never resumed");
        assertEquals(2, carrier.distinctContinuations(), "both UI fibers run on the carrier");
        assertTrue(carrier.submits() >= 3, "start, start, resume - got " + carrier.submits());
    }

    /**
     * Spins inside a UI fiber, never unmounting, until {@code start} has run a task - which only
     * happens if the started thread is scheduled somewhere other than the UI fiber's own carrier.
     */
    private void assertStartedThreadRunsBesideTheUIFiber(Consumer<Runnable> start) throws InterruptedException {
        final AtomicBoolean ran = new AtomicBoolean();
        final AtomicBoolean ranWhileMounted = new AtomicBoolean();
        final CountDownLatch uiFiberDone = new CountDownLatch(1);
        LoomUtils.newVirtualThread(carrier, "test-ui-fiber", () -> {
            start.accept(() -> ran.set(true));
            final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (!ran.get() && System.nanoTime() < deadline) {
                Thread.onSpinWait();
            }
            ranWhileMounted.set(ran.get());
            uiFiberDone.countDown();
        }).start();
        assertTrue(uiFiberDone.await(10, TimeUnit.SECONDS), "the UI fiber never finished");
        assertTrue(ranWhileMounted.get(), "the started thread was queued behind the UI fiber that started it");
        assertEquals(1, carrier.distinctContinuations(), "only the UI fiber's own continuation may reach its carrier");
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    private static void awaitWaiting(Thread thread) throws InterruptedException {
        final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (thread.getState() != Thread.State.WAITING && System.nanoTime() < deadline) {
            Thread.sleep(1);
        }
        assertEquals(Thread.State.WAITING, thread.getState(), "the UI fiber never parked");
    }

    /**
     * One carrier thread, recording every continuation it is handed.
     */
    private static final class RecordingCarrier implements Executor, AutoCloseable {
        private final ExecutorService thread = Executors.newSingleThreadExecutor(
                Thread.ofPlatform().daemon().name("test-carrier").factory());
        private final List<Runnable> submitted = Collections.synchronizedList(new ArrayList<>());

        @Override
        public void execute(Runnable command) {
            submitted.add(command);
            thread.execute(command);
        }

        int submits() {
            return submitted.size();
        }

        int distinctContinuations() {
            synchronized (submitted) {
                final Set<Runnable> distinct = Collections.newSetFromMap(new IdentityHashMap<>());
                distinct.addAll(submitted);
                return distinct.size();
            }
        }

        @Override
        public void close() {
            thread.shutdownNow();
        }
    }
}
