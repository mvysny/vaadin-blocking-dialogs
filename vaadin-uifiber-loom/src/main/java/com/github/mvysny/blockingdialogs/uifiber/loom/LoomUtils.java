/*
 * Copyright 2026 Martin Vysny
 *
 * Licensed under the MIT License. See the LICENSE file in the project root
 * for the full license text.
 */
package com.github.mvysny.blockingdialogs.uifiber.loom;

import org.jetbrains.annotations.NotNull;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Virtual threads on a scheduler of our own, which the JDK has no public API for (JDK-8308541): the
 * package-private {@code java.lang.ThreadBuilders$VirtualThreadBuilder(Executor)} does it, through
 * reflection and {@code --add-opens java.base/java.lang=ALL-UNNAMED}.
 */
final class LoomUtils {
    private LoomUtils() {
    }

    /**
     * Resolved once by {@link #builderConstructor()}.
     */
    private static volatile Constructor<?> builderConstructor;

    /**
     * Resolved once by {@link #runContinuationField()}.
     */
    private static volatile Field runContinuation;

    /**
     * Resolves the reflection up front, so that a JDK without it fails at startup rather than at the
     * first UI fiber.
     *
     * @throws IllegalStateException if this JDK lacks either of the two internals, or
     *                               {@code --add-opens} is missing.
     */
    static void checkAvailable() {
        builderConstructor();
        runContinuationField();
    }

    /**
     * A virtual thread builder whose threads run their continuations on {@code scheduler}.
     */
    @NotNull
    static Thread.Builder.OfVirtual newVirtualBuilder(@NotNull Executor scheduler) {
        Objects.requireNonNull(scheduler);
        try {
            return (Thread.Builder.OfVirtual) builderConstructor().newInstance(scheduler);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * An unstarted virtual thread that runs its continuations on {@code carrier} - and only its own:
     * <pre>{@code
     * LoomUtils.newVirtualThread(carrier, "ui-fiber", () -> {
     *     // runs on carrier
     *     Thread.ofVirtual().start(() -> {
     *         // runs on a pool of platform carriers, not on carrier
     *     });
     * }).start();
     * }</pre>
     *
     * @apiNote A virtual thread created with no explicit scheduler inherits the scheduler of the
     * virtual thread creating it - {@code Thread.ofVirtual()} and
     * {@code Executors.newVirtualThreadPerTaskExecutor()} alike. Started from this thread it would
     * run on {@code carrier}, under the session lock, queued behind the thread that started it; so
     * every other continuation goes to a JVM-wide pool of platform carriers instead.
     */
    @NotNull
    static Thread newVirtualThread(@NotNull Executor carrier, @NotNull String name, @NotNull Runnable task) {
        Objects.requireNonNull(carrier);
        final AtomicReference<Runnable> own = new AtomicReference<>();
        final Thread thread = newVirtualBuilder(continuation ->
                (continuation == own.get() ? carrier : InheritedThreadCarriers.POOL).execute(continuation))
                .name(name)
                .unstarted(task);
        own.set(continuationOf(thread));
        return thread;
    }

    /**
     * Carries the virtual threads that inherited the scheduler of a {@link #newVirtualThread}.
     * Cached rather than bounded: a continuation that blocks without unmounting holds its carrier,
     * and a bounded pool would queue unrelated threads behind it.
     */
    private static final class InheritedThreadCarriers {
        static final ExecutorService POOL = Executors.newCachedThreadPool(
                Thread.ofPlatform().daemon().name("blocking-dialogs-inherited-carrier-", 0).factory());
    }

    /**
     * The one {@code Runnable} the JDK hands a virtual thread's scheduler on every submit - at
     * start, and again at each resume after a park - so it identifies the thread from the
     * scheduler's side, which sees nothing else.
     */
    @NotNull
    private static Runnable continuationOf(@NotNull Thread virtualThread) {
        try {
            return (Runnable) runContinuationField().get(virtualThread);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
    }

    @NotNull
    private static Constructor<?> builderConstructor() {
        Constructor<?> c = builderConstructor;
        if (c == null) {
            try {
                c = Class.forName("java.lang.ThreadBuilders$VirtualThreadBuilder").getDeclaredConstructor(Executor.class);
                c.setAccessible(true);
            } catch (ReflectiveOperationException | RuntimeException e) {
                throw new IllegalStateException("Cannot reach java.lang.ThreadBuilders$VirtualThreadBuilder(Executor) on "
                        + Runtime.version() + ", which runs a virtual thread on a scheduler of our own."
                        + " Is --add-opens java.base/java.lang=ALL-UNNAMED set?", e);
            }
            builderConstructor = c;
        }
        return c;
    }

    /**
     * The private final {@code java.lang.VirtualThread.runContinuation}, set in the constructor.
     */
    @NotNull
    private static Field runContinuationField() {
        Field field = runContinuation;
        if (field == null) {
            try {
                field = Class.forName("java.lang.VirtualThread").getDeclaredField("runContinuation");
                field.setAccessible(true);
            } catch (ReflectiveOperationException | RuntimeException e) {
                throw new IllegalStateException("Cannot read java.lang.VirtualThread.runContinuation on "
                        + Runtime.version() + "; it tells a UI fiber's own continuations from those of the threads"
                        + " it starts. Is --add-opens java.base/java.lang=ALL-UNNAMED set?", e);
            }
            runContinuation = field;
        }
        return field;
    }
}
