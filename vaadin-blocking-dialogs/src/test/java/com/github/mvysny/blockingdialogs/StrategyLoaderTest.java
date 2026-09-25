/*
 * Copyright 2026 Martin Vysny
 *
 * Licensed under the MIT License. See the LICENSE file in the project root
 * for the full license text.
 */
package com.github.mvysny.blockingdialogs;

import com.vaadin.flow.component.Component;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;

public class StrategyLoaderTest {
    private static final String SERVICES = "META-INF/services/" + BlockingExecutor.class.getName();

    @TempDir
    Path dir;

    @Test
    public void getFindsTheTestStrategy() {
        final BlockingExecutor executor = BlockingExecutor.get();
        assertInstanceOf(ScriptedBlockingExecutor.class, executor);
        assertSame(executor, BlockingExecutor.get());
    }

    @Test
    public void exactlyOne() throws IOException {
        final StrategyLoader.Result result = StrategyLoader.load(withProviders(ScriptedBlockingExecutor.class.getName()));
        assertInstanceOf(ScriptedBlockingExecutor.class, result.getOrThrow());
    }

    @Test
    public void none() throws IOException {
        final StrategyLoader.Result result = StrategyLoader.load(withProviders());
        final IllegalStateException e = assertThrows(IllegalStateException.class, result::getOrThrow);
        assertTrue(e.getMessage().startsWith("No blocking strategy on the classpath"), e.getMessage());
    }

    @Test
    public void moreThanOne() throws IOException {
        final StrategyLoader.Result result = StrategyLoader.load(withProviders(
                ScriptedBlockingExecutor.class.getName(), OtherStrategy.class.getName()));
        final IllegalStateException e = assertThrows(IllegalStateException.class, result::getOrThrow);
        assertTrue(e.getMessage().contains(OtherStrategy.class.getName()), e.getMessage());
    }

    @Test
    public void failureThrowsTheSameWayOnEveryCall() throws IOException {
        final StrategyLoader.Result result = StrategyLoader.load(withProviders("com.example.NoSuchStrategy"));
        final IllegalStateException first = assertThrows(IllegalStateException.class, result::getOrThrow);
        final IllegalStateException second = assertThrows(IllegalStateException.class, result::getOrThrow);
        assertNotSame(first, second);
        assertEquals(first.getMessage(), second.getMessage());
        assertSame(first.getCause(), second.getCause());
    }

    @Test
    public void aFailingConstructorsMessageIsInTheError() throws IOException {
        final StrategyLoader.Result result = StrategyLoader.load(withProviders(RefusingStrategy.class.getName()));
        final IllegalStateException e = assertThrows(IllegalStateException.class, result::getOrThrow);
        assertTrue(e.getMessage().endsWith(": run on Java 99+"), e.getMessage());
    }

    /**
     * @return a classloader whose only {@code BlockingExecutor} services file lists {@code providers}.
     */
    private ClassLoader withProviders(String... providers) throws IOException {
        final Path services = Files.write(dir.resolve("services"), List.of(providers));
        final URL url = services.toUri().toURL();
        return new ClassLoader(StrategyLoaderTest.class.getClassLoader()) {
            @Override
            public Enumeration<URL> getResources(String name) throws IOException {
                return name.equals(SERVICES) ? Collections.enumeration(List.of(url)) : super.getResources(name);
            }
        };
    }

    /**
     * A second strategy, never registered: {@link #moreThanOne()} adds it.
     */
    public static final class OtherStrategy implements BlockingExecutor {
        @Override
        public void runLater(Runnable body) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void runUntilPark(Runnable body) {
            throw new UnsupportedOperationException();
        }

        @Override
        public <T extends @Nullable Object> T parkAndAwait(Component anchor, CompletableFuture<T> future) {
            throw new UnsupportedOperationException();
        }
    }

    /**
     * A strategy refusing to start, as the loom one does on a JDK that pins.
     */
    public static final class RefusingStrategy implements BlockingExecutor {
        public RefusingStrategy() {
            throw new IllegalStateException("run on Java 99+");
        }

        @Override
        public void runLater(Runnable body) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void runUntilPark(Runnable body) {
            throw new UnsupportedOperationException();
        }

        @Override
        public <T extends @Nullable Object> T parkAndAwait(Component anchor, CompletableFuture<T> future) {
            throw new UnsupportedOperationException();
        }
    }
}
