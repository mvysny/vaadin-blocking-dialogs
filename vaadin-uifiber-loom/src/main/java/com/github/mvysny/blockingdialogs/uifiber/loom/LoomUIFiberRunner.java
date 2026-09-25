/*
 * Copyright 2026 Martin Vysny
 *
 * Licensed under the MIT License. See the LICENSE file in the project root
 * for the full license text.
 */
package com.github.mvysny.blockingdialogs.uifiber.loom;

import com.github.mvysny.blockingdialogs.uifiber.spi.Completable;
import com.github.mvysny.blockingdialogs.uifiber.spi.UIFiberRunnerSpi;
import com.vaadin.flow.server.VaadinSession;
import org.jspecify.annotations.Nullable;

import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The loom runner: each UI fiber is a virtual thread whose continuations run as the session's access
 * tasks, so the carrier holds the session lock while the UI fiber is mounted. A park is a
 * {@link CompletableFuture#get()}: the virtual thread unmounts, the access task ends, and the lock's
 * release pushes the dialog to the browser; completing it queues the next continuation as another
 * access task.
 * <p>
 * Registered in {@code META-INF/services}; app code calls {@code vaadin-blocking-dialogs}. The app must
 * <ul>
 *     <li>serve itself from a {@link LoomVaadinServlet}, or install {@link VirtualThreadAwareLock}
 *     itself - checked by every {@link #runUntilFirstPark};</li>
 *     <li>run on Java 24+ ({@link #ALLOW_PINNING_JDK} overrides that, checked at startup), with
 *     {@code --add-opens java.base/java.lang=ALL-UNNAMED};</li>
 *     <li>serve HTTP requests from platform threads - with Vaadin Boot,
 *     {@code useVirtualThreadsIfAvailable(false)}, until virtual ones are checked in a real
 *     container: there a platform thread carries each segment of a UI fiber while the request
 *     thread waits.</li>
 * </ul>
 *
 * @apiNote Breaks the SPI's "only {@code park()} releases the lock": a UI fiber blocked on IO
 * unmounts too, and its carrier's access task ends there, releasing the lock like a park -
 * {@code runUntilFirstPark} returning at it included.
 * <p>
 * Thread-safe, and stateless app-wide.
 */
public final class LoomUIFiberRunner implements UIFiberRunnerSpi {
    /**
     * The system property that lets the runner start on Java 21-23, where a UI fiber parking inside
     * {@code synchronized} deadlocks its session - for apps stuck on 21 LTS that accept the risk.
     */
    public static final String ALLOW_PINNING_JDK = "blockingdialogs.uifiber.loom.allowPinningJdk";

    /**
     * Numbers the UI fibers' virtual threads: the runner never sees a UI to name them after.
     */
    private static final AtomicLong fiberCount = new AtomicLong();

    /**
     * Called by {@link java.util.ServiceLoader}.
     *
     * @throws IllegalStateException on Java 21-23 without {@link #ALLOW_PINNING_JDK}, or if the JDK
     *                               internals the runner reflects into are out of reach.
     */
    public LoomUIFiberRunner() {
        checkJdk(Runtime.version().feature(), Boolean.getBoolean(ALLOW_PINNING_JDK));
        LoomUtils.checkAvailable();
    }

    /**
     * @throws IllegalStateException if {@code feature} pins, unless {@code allowPinning}.
     */
    static void checkJdk(int feature, boolean allowPinning) {
        if (feature < 24 && !allowPinning) {
            throw new IllegalStateException("The loom UI fiber runner needs Java 24+, this is Java " + feature
                    + ". Before JEP 491 a virtual thread parking inside synchronized pins its carrier, and a UI fiber"
                    + " doing so deadlocks its whole Vaadin session - JDK-internal monitors included."
                    + " Run on Java 24+, or accept the risk with -D" + ALLOW_PINNING_JDK + "=true");
        }
    }

    /**
     * {@inheritDoc}
     * <p>
     * The first segment runs from inside {@link Thread#start()}, on the calling thread - on a
     * platform thread while a virtual caller waits. Any thread can carry a UI fiber, so the
     * "a thread the runner can't carry" check never throws.
     */
    @Override
    public void runUntilFirstPark(VaadinSession session, Runnable body) {
        Objects.requireNonNull(body);
        if (!session.hasLock()) {
            throw new IllegalStateException(Thread.currentThread() + " doesn't hold the lock of " + session
                    + ", so it can't start a UI fiber there");
        }
        if (VirtualThreadAwareLock.isUIVirtualThread()) {
            throw new IllegalStateException(Thread.currentThread() + " runs a UI fiber, so it can't start"
                    + " another one: the new one would need the lock this one holds");
        }
        final VirtualThreadAwareLock lock = VirtualThreadAwareLock.asVirtualThreadAware(session.getLockInstance());
        final String name = "blocking-dialogs-ui-fiber-" + fiberCount.incrementAndGet();
        LoomUtils.newVirtualThread(new SessionCarrier(session), name, () -> {
            VirtualThreadAwareLock.enterUIVirtualThread(lock);
            try {
                body.run();
            } finally {
                VirtualThreadAwareLock.exitUIVirtualThread();
            }
        }).start();
    }

    @Override
    public <R> Completable<R> newCompletable(VaadinSession session) {
        if (!VirtualThreadAwareLock.isUIVirtualThreadOf(session.getLockInstance())) {
            throw new IllegalStateException(Thread.currentThread() + " isn't a running UI fiber of " + session
                    + ", so it can't park");
        }
        return new WakeUp<>(Thread.currentThread());
    }

    /**
     * A park on a {@link CompletableFuture}: {@code get()} unmounts the UI fiber's virtual thread,
     * and completing the future submits its continuation to the {@link SessionCarrier}.
     */
    private static final class WakeUp<R> implements Completable<R> {
        private final CompletableFuture<R> future = new CompletableFuture<>();

        /**
         * The UI fiber's virtual thread, the only one allowed to {@link #park()}.
         */
        private final Thread fiber;

        /**
         * Touched by {@link #fiber} only.
         */
        private boolean parked;

        WakeUp(Thread fiber) {
            this.fiber = fiber;
        }

        @Override
        public void complete(@Nullable R value) {
            future.complete(value);
        }

        @Override
        public void fail(Throwable cause) {
            future.completeExceptionally(Objects.requireNonNull(cause));
        }

        /**
         * @implNote Unwraps a {@link CancellationException}: JDK 23+ {@code get()} throws a new one,
         * the cause {@link #fail} got attached, where older JDKs throw that cause as-is.
         */
        @Override
        public @Nullable R park() throws ExecutionException, InterruptedException {
            if (Thread.currentThread() != fiber) {
                throw new IllegalStateException(Thread.currentThread() + " can't park on a Completable of " + fiber);
            }
            if (parked) {
                throw new IllegalStateException("This Completable was parked on before");
            }
            parked = true;
            try {
                return future.get();
            } catch (CancellationException e) {
                throw e.getCause() instanceof CancellationException cause ? cause : e;
            }
        }
    }

    /**
     * Runs a UI fiber's continuations as access tasks of its session - of the session rather than the
     * UI, since a UI fiber follows its anchor to a new UI on a {@code @PreserveOnRefresh} reload.
     */
    private static final class SessionCarrier implements Executor {
        /**
         * Legitimate nesting is one virtual thread unparking another from inside its own continuation,
         * which stays shallow. A continuation that feeds itself back in recurses until the stack dies,
         * so anything in between makes a fine tripwire.
         */
        private static final int MAX_NESTED_SUBMITS = 64;

        /**
         * How deep {@link #execute} has re-entered itself on the current thread.
         */
        private static final ThreadLocal<int[]> nestedSubmits = ThreadLocal.withInitial(() -> new int[1]);

        private final VaadinSession session;

        /**
         * Whether the next submit - the UI fiber's start - mounts on the calling thread instead of being
         * queued; that submit clears it. Volatile: the later submits come from whichever thread
         * unparks the UI fiber.
         */
        private volatile boolean mountHere = true;

        SessionCarrier(VaadinSession session) {
            this.session = session;
        }

        /**
         * Queues {@code continuation} as an access task - or, for the UI fiber's start, mounts it at
         * once: {@link Thread#start()} submits on the starting thread, which holds the lock. Called on
         * whichever thread starts or unparks the UI fiber.
         *
         * @throws RejectedExecutionException if submits nest {@code MAX_NESTED_SUBMITS} deep on this
         *                                    thread: a continuation is feeding itself back in, and would
         *                                    otherwise recurse until {@link StackOverflowError}. That
         *                                    strands the UI fiber for good - the JDK moved it out of
         *                                    {@code PARKED} before calling us, so no later unpark
         *                                    resubmits it - but a stranded UI fiber can't restart the
         *                                    runaway either.
         */
        @Override
        public void execute(Runnable continuation) {
            if (mountHere) {
                mountHere = false;
                mount(continuation);
                return;
            }
            final int[] depth = nestedSubmits.get();
            if (depth[0] >= MAX_NESTED_SUBMITS) {
                throw new RejectedExecutionException("Continuation submits are " + MAX_NESTED_SUBMITS
                        + " deep on " + Thread.currentThread() + ": a virtual thread is most likely waiting for"
                        + " something that the Vaadin UI thread re-releases on every continuation."
                        + " See https://github.com/mvysny/vaadin-loom/issues/3");
            }
            depth[0]++;
            try {
                session.access(() -> mount(continuation));
            } finally {
                depth[0]--;
            }
        }

        /**
         * Runs {@code continuation} on the thread draining the access queue - whichever thread
         * releases the session lock last - or on {@code runUntilFirstPark()}'s caller, until the UI
         * fiber parks or ends. A continuation can't mount on a virtual thread
         * ({@code WrongThreadException}), so on a virtual drainer - a background virtual thread calling
         * {@code ui.access()}, a virtual request thread - a platform thread carries it while the drainer
         * waits, holding the session lock: the UI fiber still runs before the lock is really released,
         * as on a platform drainer.
         *
         * @implNote Not a handoff that takes the lock once the drainer lets go: the drainer's push
         * and a queued request would then get in before the UI fiber's segment.
         */
        private void mount(Runnable continuation) {
            if (Thread.currentThread().isVirtual()) {
                awaitUninterruptibly(Handoff.POOL.submit(continuation));
            } else {
                continuation.run();
            }
        }

        /**
         * Waits for {@code segment} even when interrupted: the UI fiber runs on the drainer's lock,
         * which the drainer mustn't let go meanwhile. The interrupt flag is restored afterwards.
         */
        private static void awaitUninterruptibly(Future<?> segment) {
            boolean interrupted = false;
            try {
                while (true) {
                    try {
                        segment.get();
                        return;
                    } catch (InterruptedException e) {
                        interrupted = true;
                    } catch (ExecutionException e) {
                        throw new IllegalStateException("A UI fiber's continuation failed on its carrier", e.getCause());
                    }
                }
            } finally {
                if (interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }

    /**
     * The platform threads that carry a continuation for a virtual drainer, one segment each. Cached:
     * a bounded pool would queue sessions behind each other.
     */
    private static final class Handoff {
        static final ExecutorService POOL = Executors.newCachedThreadPool(
                Thread.ofPlatform().daemon().name("blocking-dialogs-handoff-", 0).factory());
    }
}
