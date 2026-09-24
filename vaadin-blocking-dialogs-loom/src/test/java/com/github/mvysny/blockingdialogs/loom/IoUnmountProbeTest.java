/*
 * Copyright 2026 Martin Vysny
 *
 * Licensed under the MIT License. See the LICENSE file in the project root
 * for the full license text.
 */
package com.github.mvysny.blockingdialogs.loom;

import com.github.mvysny.blockingdialogs.BlockingDialogs;
import com.github.mvysny.blockingdialogs.ConfirmDialogOutcome;
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
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static com.github.mvysny.kaributesting.v10.LocatorJ._click;
import static com.github.mvysny.kaributesting.v10.LocatorJ._find;
import static org.junit.jupiter.api.Assertions.*;

/**
 * PROBE, not a regression test: under loom, does a block that unmounts for something other than
 * {@code parkAndAwait} release the session lock? The claim under test is {@code Q_io_unmount} in
 * {@code design/ideas/session-destroy-ends-bare-parks.md}.
 */
public class IoUnmountProbeTest {
    private static Routes routes;
    private final List<String> log = new CopyOnWriteArrayList<>();

    @BeforeAll
    public static void discoverRoutes() {
        routes = new Routes().autoDiscoverViews("com.github.mvysny.blockingdialogs.loom");
    }

    @BeforeEach
    public void setupVaadin() {
        MockVaadin.setup(MockedUI::new, new MockVirtualThreadAwareServlet(routes));
    }

    @AfterEach
    public void tearDownVaadin() {
        MockVaadin.tearDown();
    }

    /** A loopback connection whose far end writes nothing until {@link #answer()}. */
    private static final class Wire implements AutoCloseable {
        final ServerSocket server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
        final Socket client = new Socket(InetAddress.getLoopbackAddress(), server.getLocalPort());
        final Socket far = server.accept();

        Wire() throws IOException {
        }

        /** A blocking socket read, as a JDBC driver does. */
        void read() {
            try {
                client.getInputStream().read();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        void answer() throws IOException {
            far.getOutputStream().write(42);
            far.getOutputStream().flush();
        }

        @Override
        public void close() throws IOException {
            far.close();
            client.close();
            server.close();
        }
    }

    /** Roundtrips until {@code log} has {@code size} entries: the unpark arrives from the poller thread. */
    private void awaitLog(int size) throws InterruptedException {
        final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (log.size() < size && System.nanoTime() < deadline) {
            MockVaadin.clientRoundtrip();
            Thread.sleep(10);
        }
    }

    @Test
    public void aSocketReadLetsAnotherRequestRunBetweenTwoStatementsOfABlock() throws Exception {
        final AtomicInteger clicks = new AtomicInteger();
        final Button other = new Button("Another request", e -> clicks.incrementAndGet());
        UI.getCurrent().add(other);
        try (Wire wire = new Wire()) {
            BlockingDialogs.runLater(() -> {
                log.add("before IO: clicks=" + clicks.get());
                wire.read();
                log.add("after IO: clicks=" + clicks.get());
            });
            MockVaadin.clientRoundtrip();
            assertEquals(List.of("before IO: clicks=0"), log, "the block is mid-read, and the carrier moved on");

            _click(other);   // another request of the same session
            wire.answer();
            awaitLog(2);
        }
        assertEquals(List.of("before IO: clicks=0", "after IO: clicks=1"), log);
    }

    @Test
    public void aSleepLetsAnotherRequestRunBetweenTwoStatementsOfABlock() throws Exception {
        final AtomicInteger clicks = new AtomicInteger();
        final Button other = new Button("Another request", e -> clicks.incrementAndGet());
        UI.getCurrent().add(other);
        BlockingDialogs.runLater(() -> {
            log.add("before sleep: clicks=" + clicks.get());
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
            log.add("after sleep: clicks=" + clicks.get());
        });
        MockVaadin.clientRoundtrip();
        assertEquals(List.of("before sleep: clicks=0"), log);
        _click(other);
        awaitLog(2);
        assertEquals(List.of("before sleep: clicks=0", "after sleep: clicks=1"), log);
    }

    /**
     * Not Karibu's single test thread playing both roles: with the test thread out of the way, a
     * real second thread takes the session lock while the block sits mid-read.
     */
    @Test
    public void theSessionLockIsFreeWhileABlockIsMidRead() throws Exception {
        final VaadinSession session = VaadinSession.getCurrent();
        final AtomicBoolean otherThreadGotTheLock = new AtomicBoolean();
        try (Wire wire = new Wire()) {
            BlockingDialogs.runLater(() -> {
                log.add("before IO");
                wire.read();
                log.add("after IO");
            });
            MockVaadin.clientRoundtrip();
            assertEquals(List.of("before IO"), log);

            session.unlock();
            try {
                final Thread request = Thread.ofPlatform().start(() -> {
                    try {
                        if (session.getLockInstance().tryLock(2, TimeUnit.SECONDS)) {
                            otherThreadGotTheLock.set(true);
                            session.getLockInstance().unlock();
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                });
                request.join();
            } finally {
                session.lock();
            }
            assertTrue(otherThreadGotTheLock.get(), "another thread took the session lock while the block was mid-read");
            wire.answer();
            awaitLog(2);
        }
        assertEquals(List.of("before IO", "after IO"), log);
    }

    /**
     * The consequence for {@code D_input_exclusion}: a block doing IO before its first dialog
     * lets a double click in, so the blocking action runs twice.
     */
    @Test
    public void aSocketReadBeforeTheFirstDialogLetsADoubleClickIn() throws Exception {
        try (Wire wire = new Wire()) {
            final AtomicInteger blocksStarted = new AtomicInteger();
            final Button save = new Button("Save", e -> BlockingDialogs.runLater(() -> {
                blocksStarted.incrementAndGet();
                wire.read();   // "does the file exist?" against a remote store
                final ConfirmDialog dialog = new ConfirmDialog();
                dialog.setText("Overwrite?");
                if (BlockingDialogs.showAndAwait(dialog) == ConfirmDialogOutcome.CONFIRM) {
                    log.add("saved");
                }
            }));
            UI.getCurrent().add(save);
            _click(save);
            MockVaadin.clientRoundtrip();
            _click(save);   // the double click's second half
            MockVaadin.clientRoundtrip();
            wire.answer();   // one byte wakes the first read; the second block reads the next
            wire.answer();
            final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (_find(ConfirmDialog.class).size() < blocksStarted.get() && System.nanoTime() < deadline) {
                MockVaadin.clientRoundtrip();
                Thread.sleep(10);
            }
            System.out.println("PROBE double click: blocks started=" + blocksStarted.get()
                    + ", dialogs open=" + _find(ConfirmDialog.class).size());
            assertEquals(2, blocksStarted.get(), "the second click ran the blocking listener again");
        }
    }
}
