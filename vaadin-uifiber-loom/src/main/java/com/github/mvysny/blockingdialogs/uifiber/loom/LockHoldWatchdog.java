/*
 * Copyright 2026 Martin Vysny
 *
 * Licensed under the Apache License, Version 2.0. See the LICENSE file in the
 * project root for the full license text.
 */
package com.github.mvysny.blockingdialogs.uifiber.loom;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * The one-slot hand-off of a UI fiber's next continuation to the carrier that holds the session lock
 * for it across an unmount other than a {@code park()}: whichever thread wakes the UI fiber
 * {@link #offer}s, the carrier {@link #take}s. A {@code take()} that waits long WARNs with the UI
 * fiber's stack, as nothing else shows it - the JVM's deadlock detector finds no cycle through this
 * ownerless queue, and {@code jstack} omits virtual threads:
 * <pre>
 * WARN UI fiber blocking-dialogs-ui-fiber-7 has held the session lock for 10 s outside park(), ...
 * java.lang.Throwable: blocking-dialogs-ui-fiber-7 is here
 *     at java.base/sun.nio.ch.NioSocketImpl.read(NioSocketImpl.java:...)
 *     at com.example.OrdersView.cancel(OrdersView.java:42)
 * WARN UI fiber blocking-dialogs-ui-fiber-7 resumed after 42 s
 * </pre>
 * <p>
 * Thread-safe.
 */
final class LockHoldWatchdog {
    private static final Logger log = LoggerFactory.getLogger(LockHoldWatchdog.class);

    private final BlockingQueue<Runnable> next = new ArrayBlockingQueue<>(1);

    private final Thread fiber;

    private final Duration firstWarn;

    /**
     * @param fiber     the UI fiber's virtual thread, whose stack a WARN shows.
     * @param firstWarn how long {@link #take} waits before its first WARN; {@link Duration#ZERO}
     *                  never WARNs.
     */
    LockHoldWatchdog(Thread fiber, Duration firstWarn) {
        this.fiber = Objects.requireNonNull(fiber);
        if (firstWarn.isNegative()) {
            throw new IllegalArgumentException("firstWarn must not be negative: " + firstWarn);
        }
        this.firstWarn = firstWarn;
    }

    /**
     * A watchdog for {@code fiber} that WARNs after {@link LoomUIFiberRunner#LOCK_HOLD_WARN_SECONDS}.
     */
    static LockHoldWatchdog of(Thread fiber) {
        return new LockHoldWatchdog(fiber, Duration.ofSeconds(Long.getLong(LoomUIFiberRunner.LOCK_HOLD_WARN_SECONDS, 10)));
    }

    /**
     * Hands the UI fiber's next continuation to the carrier, without blocking: it may be called on
     * the carrier itself, from inside the continuation that is yielding.
     *
     * @throws IllegalStateException if a continuation is already waiting: a UI fiber has at most one.
     */
    void offer(Runnable continuation) {
        if (!next.offer(Objects.requireNonNull(continuation))) {
            throw new IllegalStateException(fiber + " was submitted twice before its carrier took it");
        }
    }

    /**
     * Waits for the UI fiber's next continuation, even when interrupted: the carrier holds the lock
     * for the UI fiber, and mustn't let go of it mid-IO. The interrupt flag is restored afterwards.
     */
    Runnable take() {
        final long start = System.nanoTime();
        long warnAtNanos = firstWarn.toNanos();
        boolean warned = false;
        boolean interrupted = false;
        try {
            while (true) {
                try {
                    final @Nullable Runnable continuation = warnAtNanos == 0
                            ? next.take()
                            : next.poll(warnAtNanos - (System.nanoTime() - start), TimeUnit.NANOSECONDS);
                    if (continuation != null) {
                        if (warned) {
                            log.warn("UI fiber {} resumed after {} s", fiber.getName(), secondsSince(start));
                        }
                        return continuation;
                    }
                    warn(secondsSince(start));
                    warned = true;
                    warnAtNanos *= 3;
                } catch (InterruptedException e) {
                    interrupted = true;
                }
            }
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void warn(long seconds) {
        final Throwable here = new Throwable(fiber.getName() + " is here");
        here.setStackTrace(fiber.getStackTrace());
        log.warn("UI fiber {} has held the session lock for {} s outside park(), freezing its session."
                + " Blocked on a bare Future.get(), a database lock, or just a slow query?"
                + " Waiting on, holding the lock; -D{}=0 silences this", fiber.getName(), seconds,
                LoomUIFiberRunner.LOCK_HOLD_WARN_SECONDS, here);
    }

    private static long secondsSince(long startNanos) {
        return TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - startNanos);
    }
}
