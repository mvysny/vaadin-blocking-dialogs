/*
 * Copyright 2026 Martin Vysny
 *
 * Licensed under the Apache License, Version 2.0. See the LICENSE file in the
 * project root for the full license text.
 */
package com.github.mvysny.blockingdialogs;

import com.github.mvysny.blockingdialogs.uifiber.loom.LoomUIFiberRunner;
import com.github.mvysny.blockingdialogs.uifiber.spi.Completable;
import com.github.mvysny.blockingdialogs.uifiber.spi.UIFiberRunnerSpi;
import com.vaadin.flow.server.VaadinSession;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class RunnerLoaderTest {
    private static final String SERVICES = "META-INF/services/" + UIFiberRunnerSpi.class.getName();

    @TempDir
    Path dir;

    @Test
    public void getFindsTheLoomRunner() {
        final UIFiberRunnerSpi runner = RunnerLoader.get();
        assertInstanceOf(LoomUIFiberRunner.class, runner);
        assertSame(runner, RunnerLoader.get());
    }

    @Test
    public void exactlyOne() throws IOException {
        final RunnerLoader.Result result = RunnerLoader.load(withProviders(OtherRunner.class.getName()));
        assertInstanceOf(OtherRunner.class, result.getOrThrow());
    }

    @Test
    public void none() throws IOException {
        final RunnerLoader.Result result = RunnerLoader.load(withProviders());
        final IllegalStateException e = assertThrows(IllegalStateException.class, result::getOrThrow);
        assertTrue(e.getMessage().startsWith("No UI fiber runner on the classpath"), e.getMessage());
    }

    @Test
    public void moreThanOne() throws IOException {
        final RunnerLoader.Result result = RunnerLoader.load(withProviders(
                LoomUIFiberRunner.class.getName(), OtherRunner.class.getName()));
        final IllegalStateException e = assertThrows(IllegalStateException.class, result::getOrThrow);
        assertTrue(e.getMessage().contains(OtherRunner.class.getName()), e.getMessage());
    }

    @Test
    public void failureThrowsTheSameWayOnEveryCall() throws IOException {
        final RunnerLoader.Result result = RunnerLoader.load(withProviders("com.example.NoSuchRunner"));
        final IllegalStateException first = assertThrows(IllegalStateException.class, result::getOrThrow);
        final IllegalStateException second = assertThrows(IllegalStateException.class, result::getOrThrow);
        assertNotSame(first, second);
        assertEquals(first.getMessage(), second.getMessage());
        assertSame(first.getCause(), second.getCause());
    }

    @Test
    public void aFailingConstructorsMessageIsInTheError() throws IOException {
        final RunnerLoader.Result result = RunnerLoader.load(withProviders(RefusingRunner.class.getName()));
        final IllegalStateException e = assertThrows(IllegalStateException.class, result::getOrThrow);
        assertTrue(e.getMessage().endsWith(": run on Java 99+"), e.getMessage());
    }

    /**
     * @return a classloader whose only {@code UIFiberRunnerSpi} services file lists {@code providers}.
     */
    private ClassLoader withProviders(String... providers) throws IOException {
        final Path services = Files.write(dir.resolve("services"), List.of(providers));
        final URL url = services.toUri().toURL();
        return new ClassLoader(RunnerLoaderTest.class.getClassLoader()) {
            @Override
            public Enumeration<URL> getResources(String name) throws IOException {
                return name.equals(SERVICES) ? Collections.enumeration(List.of(url)) : super.getResources(name);
            }
        };
    }

    /**
     * A second runner, never registered: the tests add it.
     */
    public static class OtherRunner implements UIFiberRunnerSpi {
        @Override
        public void runUntilFirstPark(VaadinSession session, Runnable body) {
            throw new UnsupportedOperationException();
        }

        @Override
        public <R> Completable<R> newCompletable(VaadinSession session) {
            throw new UnsupportedOperationException();
        }
    }

    /**
     * A runner refusing to start, as the loom one does on a JDK that pins.
     */
    public static final class RefusingRunner extends OtherRunner {
        public RefusingRunner() {
            throw new IllegalStateException("run on Java 99+");
        }
    }
}
