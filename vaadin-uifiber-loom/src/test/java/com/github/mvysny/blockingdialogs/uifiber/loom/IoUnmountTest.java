/*
 * Copyright 2026 Martin Vysny
 *
 * Licensed under the Apache License, Version 2.0. See the LICENSE file in the
 * project root for the full license text.
 */
package com.github.mvysny.blockingdialogs.uifiber.loom;

import com.github.mvysny.blockingdialogs.BlockingDialogs;
import com.github.mvysny.blockingdialogs.ConfirmDialogOutcome;
import com.github.mvysny.blockingdialogs.UIFibers;
import com.github.mvysny.kaributesting.v10.MockVaadin;
import com.github.mvysny.kaributesting.v10.Routes;
import com.github.mvysny.kaributesting.v10.mock.MockedUI;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.confirmdialog.ConfirmDialog;
import com.vaadin.flow.server.VaadinSession;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static com.github.mvysny.kaributesting.v10.LocatorJ._click;
import static com.github.mvysny.kaributesting.v10.LocatorJ._find;
import static org.junit.jupiter.api.Assertions.*;

/**
 * A UI fiber that unmounts for anything but a {@code park()} - a socket read, {@code sleep}, a
 * yield - keeps the session lock: the UIFiberRunnerSpi's "The lock". A platform "request" thread
 * queues on the lock meanwhile, and must get it only once the UI fiber parks or ends.
 */
public class IoUnmountTest {
    private static Routes routes;
    private final List<String> log = new CopyOnWriteArrayList<>();

    @BeforeAll
    public static void discoverRoutes() {
        routes = new Routes().autoDiscoverViews("com.github.mvysny.blockingdialogs.uifiber.loom");
    }

    @BeforeEach
    public void setupVaadin() {
        MockVaadin.setup(MockedUI::new, new MockVirtualThreadAwareServlet(routes));
    }

    @AfterEach
    public void tearDownVaadin() {
        MockVaadin.tearDown();
    }

    /**
     * A loopback connection whose far end writes nothing until {@link #answerLater()}.
     */
    private static final class Wire implements AutoCloseable {
        final ServerSocket server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
        final Socket client = new Socket(InetAddress.getLoopbackAddress(), server.getLocalPort());
        final Socket far = server.accept();

        Wire() throws IOException {
        }

        /**
         * A blocking socket read, as a JDBC driver does.
         */
        void read() {
            try {
                client.getInputStream().read();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        /**
         * Writes one byte from another thread in 200 ms: the test thread may be the carrier, blocked
         * in the read, so it can't answer itself.
         */
        void answerLater() {
            Thread.ofPlatform().start(() -> {
                try {
                    Thread.sleep(200);
                    far.getOutputStream().write(42);
                    far.getOutputStream().flush();
                } catch (IOException | InterruptedException e) {
                    throw new RuntimeException(e);
                }
            });
        }

        @Override
        public void close() throws IOException {
            far.close();
            client.close();
            server.close();
        }
    }

    /**
     * A platform thread that takes the session lock the way a request does, and runs {@code body}
     * holding it; returns once the thread queues on the lock the test thread holds.
     */
    private static Thread queueARequest(Runnable body) {
        final VaadinSession session = VaadinSession.getCurrent();
        final Thread request = Thread.ofPlatform().start(() -> {
            session.lock();
            try {
                body.run();
            } finally {
                session.unlock();
            }
        });
        final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (request.getState() != Thread.State.WAITING && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
        assertEquals(Thread.State.WAITING, request.getState(), "the request queued on the session lock");
        return request;
    }

    /**
     * Lets the queued {@code request} have the lock, and takes it back once the request is done.
     */
    private static void letIn(Thread request) throws InterruptedException {
        final VaadinSession session = VaadinSession.getCurrent();
        session.unlock();
        try {
            assertTrue(request.join(Duration.ofSeconds(5)), "the request never got the lock");
        } finally {
            session.lock();
        }
    }

    @Test
    public void aSocketReadKeepsTheSessionLock() throws Exception {
        try (Wire wire = new Wire()) {
            UIFibers.runLater(() -> {
                log.add("before IO");
                wire.read();
                log.add("after IO");
            });
            final Thread request = queueARequest(() -> log.add("request"));
            wire.answerLater();
            MockVaadin.clientRoundtrip();
            letIn(request);
        }
        assertEquals(List.of("before IO", "after IO", "request"), log);
    }

    @Test
    public void aSleepKeepsTheSessionLock() throws Exception {
        UIFibers.runLater(() -> {
            log.add("before sleep");
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
            log.add("after sleep");
        });
        final Thread request = queueARequest(() -> log.add("request"));
        MockVaadin.clientRoundtrip();
        letIn(request);
        assertEquals(List.of("before sleep", "after sleep", "request"), log);
    }

    /**
     * A yield resubmits the UI fiber on its carrier, from inside the continuation still returning.
     */
    @Test
    public void aYieldKeepsTheSessionLock() throws Exception {
        UIFibers.runLater(() -> {
            log.add("before yield");
            Thread.yield();
            log.add("after yield");
        });
        final Thread request = queueARequest(() -> log.add("request"));
        MockVaadin.clientRoundtrip();
        letIn(request);
        assertEquals(List.of("before yield", "after yield", "request"), log);
    }

    @Test
    public void aSocketReadAfterAParkKeepsTheSessionLock() throws Exception {
        final CompletableFuture<String> answer = new CompletableFuture<>();
        try (Wire wire = new Wire()) {
            UIFibers.runLater(() -> {
                log.add("resumed: " + UIFibers.parkAndAwait(UI.getCurrent(), answer));
                wire.read();
                log.add("after IO");
            });
            MockVaadin.clientRoundtrip();
            assertEquals(List.of(), log, "parked");

            answer.complete("yes");
            final Thread request = queueARequest(() -> log.add("request"));
            wire.answerLater();
            MockVaadin.clientRoundtrip();
            letIn(request);
        }
        assertEquals(List.of("resumed: yes", "after IO", "request"), log);
    }

    /**
     * {@code D_input_exclusion}: IO before the first dialog doesn't let a double click in - the
     * second click finds the dialog open, for Vaadin's server-side modality to drop it.
     */
    @Test
    public void aSocketReadBeforeTheFirstDialogLetsNoDoubleClickIn() throws Exception {
        final AtomicInteger uiFibersStarted = new AtomicInteger();
        final UI ui = UI.getCurrent();
        try (Wire wire = new Wire()) {
            final Button save = new Button("Save", e -> UIFibers.runLater(() -> {
                uiFibersStarted.incrementAndGet();
                wire.read();   // "does the file exist?" against a remote store
                final ConfirmDialog dialog = new ConfirmDialog();
                dialog.setText("Overwrite?");
                if (BlockingDialogs.showAndAwait(dialog) == ConfirmDialogOutcome.CONFIRM) {
                    log.add("saved");
                }
            }));
            ui.add(save);
            _click(save);
            final Thread secondClick = queueARequest(() ->
                    log.add("second click finds a modal: " + ui.getInternals().hasModalComponent()));
            wire.answerLater();
            MockVaadin.clientRoundtrip();
            letIn(secondClick);
        }
        assertEquals(List.of("second click finds a modal: true"), log);
        assertEquals(1, uiFibersStarted.get());
        assertEquals(1, _find(ConfirmDialog.class).size());
    }

    /**
     * {@code D_run_until_park}: the first park, never an IO wait.
     */
    @Test
    public void runUntilParkReturnsAtTheFirstParkNotAtASocketRead() throws Exception {
        final CompletableFuture<String> answer = new CompletableFuture<>();
        try (Wire wire = new Wire()) {
            wire.answerLater();
            UIFibers.runUntilPark(() -> {
                wire.read();
                log.add("after IO");
                log.add("resumed: " + UIFibers.parkAndAwait(UI.getCurrent(), answer));
            });
            assertEquals(List.of("after IO"), log, "runUntilPark returned at the park");
        }
        answer.complete("yes");
        MockVaadin.clientRoundtrip();
        assertEquals(List.of("after IO", "resumed: yes"), log);
    }
}
