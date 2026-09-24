/*
 * Copyright 2026 Martin Vysny
 *
 * Licensed under the MIT License. See the LICENSE file in the project root
 * for the full license text.
 */
package com.github.mvysny.blockingdialogs;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;

/**
 * {@link BlockingExecutor#get()}: the strategy, looked up once per classloader.
 *
 * @implNote Cached because every {@code ServiceLoader.load()} re-scans {@code META-INF/services},
 * and {@code get()} runs on every {@code runLater()}. A failure is cached too, as a message rather
 * than an exception instance, so each call throws a fresh exception with its own stack trace.
 */
final class StrategyLoader {
    private StrategyLoader() {
    }

    /**
     * The outcome of a lookup: exactly one of {@code executor} and {@code error} is set.
     */
    record Result(@Nullable BlockingExecutor executor, @Nullable String error, @Nullable Throwable cause) {
        @NotNull
        BlockingExecutor getOrThrow() {
            if (executor == null) {
                throw new IllegalStateException(error, cause);
            }
            return executor;
        }
    }

    private static final class Holder {
        @NotNull
        static final Result result = load(BlockingExecutor.class.getClassLoader());
    }

    @NotNull
    static BlockingExecutor get() {
        return Holder.result.getOrThrow();
    }

    @NotNull
    static Result load(@NotNull ClassLoader classLoader) {
        final List<BlockingExecutor> found = new ArrayList<>();
        try {
            ServiceLoader.load(BlockingExecutor.class, classLoader).forEach(found::add);
        } catch (ServiceConfigurationError e) {
            // a provider's constructor failing is the cause; its message is the one naming the fix
            final String reason = e.getCause() == null ? e.getMessage() : e.getMessage() + ": " + e.getCause().getMessage();
            return new Result(null, "A blocking strategy on the classpath failed to load: " + reason, e);
        }
        if (found.isEmpty()) {
            return new Result(null, "No blocking strategy on the classpath: add one, such as"
                    + " vaadin-blocking-dialogs-loom", null);
        }
        if (found.size() > 1) {
            return new Result(null, found.size() + " blocking strategies on the classpath, "
                    + found.stream().map(it -> it.getClass().getName()).toList()
                    + ": keep exactly one", null);
        }
        return new Result(found.get(0), null, null);
    }
}
