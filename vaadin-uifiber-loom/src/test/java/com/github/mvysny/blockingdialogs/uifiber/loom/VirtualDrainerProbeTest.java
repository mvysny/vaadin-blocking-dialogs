/*
 * Copyright 2026 Martin Vysny
 *
 * Licensed under the MIT License. See the LICENSE file in the project root
 * for the full license text.
 */
package com.github.mvysny.blockingdialogs.uifiber.loom;

import com.github.mvysny.blockingdialogs.BlockingDialogs;
import com.github.mvysny.kaributesting.v10.MockVaadin;
import com.github.mvysny.kaributesting.v10.Routes;
import com.github.mvysny.kaributesting.v10.mock.MockedUI;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.server.VaadinSession;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * PROBE: a continuation reaching a <i>virtual</i> drainer - a background virtual thread whose
 * {@code ui.access()} found the session lock free - runs before that drainer lets the lock go, as
 * on a platform drainer. The claim under test is {@code Q_run_later_derived}'s proposal in
 * {@code design/ideas/spi.md}.
 * <p>
 * The test thread lets the session lock go, so that the virtual thread is the drainer. A platform
 * "request" thread queues on the lock while the virtual thread drains; the fiber's segment must come
 * first.
 */
public class VirtualDrainerProbeTest {
    private static Routes routes;
    private final List<String> log = new CopyOnWriteArrayList<>();
    private final List<Throwable> reportedErrors = new CopyOnWriteArrayList<>();

    @BeforeAll
    public static void discoverRoutes() {
        routes = new Routes().autoDiscoverViews("com.github.mvysny.blockingdialogs.uifiber.loom");
    }

    @BeforeEach
    public void setupVaadin() {
        MockVaadin.setup(MockedUI::new, new MockVirtualThreadAwareServlet(routes));
        VaadinSession.getCurrent().setErrorHandler(event -> reportedErrors.add(event.getThrowable()));
    }

    @AfterEach
    public void tearDownVaadin() {
        MockVaadin.tearDown();
    }

    /**
     * A platform thread that takes the session lock the way a request does, logs, and lets it go.
     */
    private Thread queueARequest(VaadinSession session) {
        final Thread request = Thread.ofPlatform().start(() -> {
            session.lock();
            try {
                log.add("request");
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
     * Runs {@code body} on a background virtual thread as an access task, with the session lock free,
     * so that the virtual thread drains the queue; then waits for {@code request}.
     */
    private void drainOnAVirtualThread(Runnable body) throws InterruptedException {
        final UI ui = UI.getCurrent();
        final VaadinSession session = VaadinSession.getCurrent();
        final Thread[] request = new Thread[1];
        session.unlock();
        try {
            Thread.ofVirtual().start(() -> {
                ui.access(() -> {
                    request[0] = queueARequest(session);
                    body.run();
                });
                log.add("drainer released");
            }).join();
            request[0].join(TimeUnit.SECONDS.toMillis(5));
        } finally {
            session.lock();
        }
    }

    @Test
    public void aFiberStartDrainedByAVirtualThreadRunsBeforeTheDrainerReleases() throws InterruptedException {
        final CompletableFuture<String> answer = new CompletableFuture<>();
        drainOnAVirtualThread(() -> BlockingDialogs.runLater(() -> {
            log.add("segment");
            log.add("resumed: " + BlockingDialogs.parkAndAwait(UI.getCurrent(), answer));
        }));
        assertEquals(List.of("segment", "drainer released", "request"), log);

        answer.complete("yes");
        MockVaadin.clientRoundtrip();
        assertEquals(List.of("segment", "drainer released", "request", "resumed: yes"), log);
        assertEquals(List.of(), reportedErrors);
    }

    @Test
    public void aWakeUpDrainedByAVirtualThreadRunsBeforeTheDrainerReleases() throws InterruptedException {
        final CompletableFuture<String> answer = new CompletableFuture<>();
        BlockingDialogs.runLater(() -> log.add("resumed: " + BlockingDialogs.parkAndAwait(UI.getCurrent(), answer)));
        MockVaadin.clientRoundtrip();
        assertEquals(List.of(), log, "parked");

        drainOnAVirtualThread(() -> answer.complete("yes"));
        assertEquals(List.of("resumed: yes", "drainer released", "request"), log);
        assertEquals(List.of(), reportedErrors);
    }

    @Test
    public void aCascadeSettlesInTheVirtualThreadsDrain() throws InterruptedException {
        final CompletableFuture<String> answerA = new CompletableFuture<>();
        final CompletableFuture<String> answerB = new CompletableFuture<>();
        BlockingDialogs.runLater(() -> {
            log.add("A resumed: " + BlockingDialogs.parkAndAwait(UI.getCurrent(), answerA));
            answerB.complete("from A");
        });
        BlockingDialogs.runLater(() -> log.add("B resumed: " + BlockingDialogs.parkAndAwait(UI.getCurrent(), answerB)));
        MockVaadin.clientRoundtrip();
        assertEquals(List.of(), log, "both parked");

        drainOnAVirtualThread(() -> answerA.complete("yes"));
        assertEquals(List.of("A resumed: yes", "B resumed: from A", "drainer released", "request"), log);
        assertEquals(List.of(), reportedErrors);
    }

    @Test
    public void runUntilParkFromAVirtualThreadReturnsAtTheFirstPark() throws InterruptedException {
        final UI ui = UI.getCurrent();
        final VaadinSession session = VaadinSession.getCurrent();
        final CompletableFuture<String> answer = new CompletableFuture<>();
        session.unlock();
        try {
            Thread.ofVirtual().start(() -> ui.accessSynchronously(() -> {
                BlockingDialogs.runUntilPark(() -> {
                    log.add("segment");
                    log.add("resumed: " + BlockingDialogs.parkAndAwait(ui, answer));
                });
                log.add("runUntilPark returned");
            })).join();
        } finally {
            session.lock();
        }
        assertEquals(List.of("segment", "runUntilPark returned"), log);

        answer.complete("yes");
        MockVaadin.clientRoundtrip();
        assertEquals(List.of("segment", "runUntilPark returned", "resumed: yes"), log);
        assertEquals(List.of(), reportedErrors);
    }
}
