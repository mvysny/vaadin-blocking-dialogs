/*
 * Copyright 2026 Martin Vysny
 *
 * Licensed under the Apache License, Version 2.0. See the LICENSE file in the
 * project root for the full license text.
 */
package com.github.mvysny.blockingdialogs;

import com.github.mvysny.blockingdialogs.uifiber.spi.UIFiberRunnerSpi;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;

/**
 * The {@link UIFiberRunnerSpi} on the classpath, found through {@link ServiceLoader} once per
 * classloader: exactly one, or every call throws.
 *
 * @implNote Cached because every {@code ServiceLoader.load()} re-scans {@code META-INF/services},
 * and {@link #get()} runs on every {@code runLater()}. A failure is cached too, as a message rather
 * than an exception instance, so each call throws a fresh exception with its own stack trace.
 */
final class RunnerLoader {
    private RunnerLoader() {
    }

    /**
     * The outcome of a lookup: exactly one of {@code runner} and {@code error} is set.
     */
    record Result(@Nullable UIFiberRunnerSpi runner, @Nullable String error, @Nullable Throwable cause) {
        UIFiberRunnerSpi getOrThrow() {
            if (runner == null) {
                throw new IllegalStateException(error, cause);
            }
            return runner;
        }
    }

    private static final class Holder {
        static final Result result = load(UIFiberRunnerSpi.class.getClassLoader());
    }

    /**
     * @throws IllegalStateException if there is none, or more than one - on every call alike.
     */
    static UIFiberRunnerSpi get() {
        return Holder.result.getOrThrow();
    }

    static Result load(ClassLoader classLoader) {
        final List<UIFiberRunnerSpi> found = new ArrayList<>();
        try {
            ServiceLoader.load(UIFiberRunnerSpi.class, classLoader).forEach(found::add);
        } catch (ServiceConfigurationError e) {
            // a provider's constructor failing is the cause; its message is the one naming the fix
            final String reason = e.getCause() == null ? e.getMessage() : e.getMessage() + ": " + e.getCause().getMessage();
            return new Result(null, "A UI fiber runner on the classpath failed to load: " + reason, e);
        }
        if (found.isEmpty()) {
            return new Result(null, "No UI fiber runner on the classpath: add one, such as"
                    + " vaadin-uifiber-loom", null);
        }
        if (found.size() > 1) {
            return new Result(null, found.size() + " UI fiber runners on the classpath, "
                    + found.stream().map(it -> it.getClass().getName()).toList()
                    + ": keep exactly one", null);
        }
        return new Result(found.get(0), null, null);
    }
}
