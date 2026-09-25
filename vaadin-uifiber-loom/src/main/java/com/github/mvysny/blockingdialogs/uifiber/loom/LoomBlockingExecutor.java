/*
 * Copyright 2026 Martin Vysny
 *
 * Licensed under the MIT License. See the LICENSE file in the project root
 * for the full license text.
 */
package com.github.mvysny.blockingdialogs.uifiber.loom;

import com.github.mvysny.blockingdialogs.BlockingExecutor;
import com.github.mvysny.blockingdialogs.StrategySupport;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.server.VaadinSession;
import org.jspecify.annotations.Nullable;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;

/**
 * The loom strategy: each UI fiber is a virtual thread whose continuations run as the session's access
 * tasks, so the carrier holds the session lock while the UI fiber is mounted. A park is a plain
 * {@link CompletableFuture#get()}: the virtual thread unmounts, the access task ends, and the
 * lock's release pushes the dialog to the browser; completing the future queues the next
 * continuation as another access task.
 * <p>
 * Registered in {@code META-INF/services}; app code calls {@code BlockingDialogs}. The app must
 * <ul>
 *     <li>serve itself from a {@link LoomVaadinServlet}, or install {@link VirtualThreadAwareLock}
 *     itself - checked by every {@link #runLater};</li>
 *     <li>run on Java 24+ ({@link #ALLOW_PINNING_JDK} overrides that, checked at startup), with
 *     {@code --add-opens java.base/java.lang=ALL-UNNAMED};</li>
 *     <li>serve HTTP requests from platform threads - with Vaadin Boot,
 *     {@code useVirtualThreadsIfAvailable(false)}, until virtual ones are checked in a real
 *     container: there a platform thread carries each segment of a UI fiber while the request
 *     thread waits.</li>
 * </ul>
 * <p>
 * Thread-safe, and stateless app-wide.
 */
public final class LoomBlockingExecutor implements BlockingExecutor {
    /**
     * The system property that lets the strategy start on Java 21-23, where a UI fiber parking inside
     * {@code synchronized} deadlocks its session - for apps stuck on 21 LTS that accept the risk.
     */
    public static final String ALLOW_PINNING_JDK = "blockingdialogs.uifiber.loom.allowPinningJdk";

    /**
     * Called by {@link java.util.ServiceLoader}.
     *
     * @throws IllegalStateException on Java 21-23 without {@link #ALLOW_PINNING_JDK}, or if the JDK
     *                               internals the strategy reflects into are out of reach.
     */
    public LoomBlockingExecutor() {
        checkJdk(Runtime.version().feature(), Boolean.getBoolean(ALLOW_PINNING_JDK));
        LoomUtils.checkAvailable();
    }

    /**
     * @throws IllegalStateException if {@code feature} pins, unless {@code allowPinning}.
     */
    static void checkJdk(int feature, boolean allowPinning) {
        if (feature < 24 && !allowPinning) {
            throw new IllegalStateException("The loom blocking strategy needs Java 24+, this is Java " + feature
                    + ". Before JEP 491 a virtual thread parking inside synchronized pins its carrier, and a UI fiber"
                    + " doing so deadlocks its whole Vaadin session - JDK-internal monitors included."
                    + " Run on Java 24+, or accept the risk with -D" + ALLOW_PINNING_JDK + "=true");
        }
    }

    @Override
    public void runLater(Runnable body) {
        Objects.requireNonNull(body);
        start(StrategySupport.checkLockedUI(), body, false);
    }

    /**
     * {@inheritDoc}
     * <p>
     * The UI fiber's first segment runs from inside {@link Thread#start()}, on the calling thread -
     * on a platform thread while a virtual caller waits - rather than as an access task: access tasks
     * queued earlier still wait for the lock's release.
     */
    @Override
    public void runUntilPark(Runnable body) {
        Objects.requireNonNull(body);
        final UI ui = StrategySupport.checkLockedUI();
        if (StrategySupport.isInUIFiber()) {
            StrategySupport.runUIFiber(ui, body);
            return;
        }
        start(ui, body, true);
    }

    /**
     * @param mountHere whether the UI fiber's first segment runs on the calling thread before this
     *                  returns, rather than queued as an access task.
     */
    private static void start(UI ui, Runnable body, boolean mountHere) {
        final VaadinSession session = ui.getSession();
        final VirtualThreadAwareLock lock = VirtualThreadAwareLock.asVirtualThreadAware(session.getLockInstance());
        LoomUtils.newVirtualThread(new SessionCarrier(session, mountHere), "blocking-dialogs-ui-" + ui.getUIId(), () -> {
            VirtualThreadAwareLock.enterUIVirtualThread(lock);
            try {
                StrategySupport.runUIFiber(ui, body);
            } finally {
                VirtualThreadAwareLock.exitUIVirtualThread();
            }
        }).start();
    }

    @Override
    public <T extends @Nullable Object> T parkAndAwait(Component anchor, CompletableFuture<T> future) {
        Objects.requireNonNull(future);
        checkInUIFiber();
        return StrategySupport.awaitAnchored(anchor, future, future::get);
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
        private volatile boolean mountHere;

        SessionCarrier(VaadinSession session, boolean mountHere) {
            this.session = session;
            this.mountHere = mountHere;
        }

        /**
         * Queues {@code continuation} as an access task - or, for {@code runUntilPark()}'s start,
         * mounts it at once: {@link Thread#start()} submits on the starting thread, which holds the
         * lock on a platform thread. Called on whichever thread starts or unparks the UI fiber.
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
         * releases the session lock last - or on {@code runUntilPark()}'s caller, until the UI fiber
         * parks or ends. A continuation can't mount on a virtual thread ({@code WrongThreadException}),
         * so on a virtual drainer - a background virtual thread calling {@code ui.access()}, a virtual
         * request thread - a platform thread carries it while the drainer waits, holding the session
         * lock: the UI fiber still runs before the lock is really released, as on a platform drainer.
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
