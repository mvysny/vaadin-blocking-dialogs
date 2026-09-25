/*
 * Copyright 2026 Martin Vysny
 *
 * Licensed under the Apache License, Version 2.0. See the LICENSE file in the
 * project root for the full license text.
 */
package com.github.mvysny.blockingdialogs.uifiber.loom;

import com.github.mvysny.blockingdialogs.UIFibers;
import com.github.mvysny.kaributesting.v10.MockVaadin;
import com.vaadin.flow.component.UI;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A continuation reaching a <i>virtual</i> drainer - a background virtual thread whose
 * {@code ui.access()} found the session lock free - runs before that drainer lets the lock go, as
 * on a platform drainer: {@code runLater} is {@code access(runUntilFirstPark)} on every drainer.
 * <p>
 * The test thread lets the session lock go, so that the virtual thread is the drainer. A platform
 * "request" thread queues on the lock while the virtual thread drains; the fiber's segment must come
 * first.
 */
public class VirtualDrainerTest {
    private final List<String> log = new CopyOnWriteArrayList<>();
    private List<Throwable> reportedErrors;

    @BeforeEach
    public void setupVaadin() {
        reportedErrors = LoomTests.setupVaadin();
    }

    @AfterEach
    public void tearDownVaadin() {
        MockVaadin.tearDown();
    }

    /**
     * A request that logs once it gets the session lock.
     * <p>
     * The drainer logs its release only once {@code access()} returns, after the lock is already free,
     * so the request waits for that entry before logging its own; otherwise the two race.
     */
    private Thread queueARequest(CountDownLatch drainerReleased) {
        return LoomTests.queueARequest(() -> {
            try {
                if (!drainerReleased.await(5, TimeUnit.SECONDS)) {
                    log.add("request took the lock before the drainer returned");
                }
                log.add("request");
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        });
    }

    /**
     * Runs {@code body} on a background virtual thread as an access task, with the session lock free,
     * so that the virtual thread drains the queue; then waits for {@code request}.
     */
    private void drainOnAVirtualThread(Runnable body) throws InterruptedException {
        final UI ui = UI.getCurrent();
        final Thread[] request = new Thread[1];
        final CountDownLatch drainerReleased = new CountDownLatch(1);
        LoomTests.withSessionLockReleased(() -> {
            Thread.ofVirtual().start(() -> {
                ui.access(() -> {
                    request[0] = queueARequest(drainerReleased);
                    body.run();
                });
                log.add("drainer released");
                drainerReleased.countDown();
            }).join();
            request[0].join(TimeUnit.SECONDS.toMillis(5));
        });
    }

    @Test
    public void aFiberStartDrainedByAVirtualThreadRunsBeforeTheDrainerReleases() throws InterruptedException {
        final CompletableFuture<String> answer = new CompletableFuture<>();
        drainOnAVirtualThread(() -> UIFibers.runLater(() -> {
            log.add("segment");
            log.add("resumed: " + UIFibers.parkAndAwait(UI.getCurrent(), answer));
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
        UIFibers.runLater(() -> log.add("resumed: " + UIFibers.parkAndAwait(UI.getCurrent(), answer)));
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
        UIFibers.runLater(() -> {
            log.add("A resumed: " + UIFibers.parkAndAwait(UI.getCurrent(), answerA));
            answerB.complete("from A");
        });
        UIFibers.runLater(() -> log.add("B resumed: " + UIFibers.parkAndAwait(UI.getCurrent(), answerB)));
        MockVaadin.clientRoundtrip();
        assertEquals(List.of(), log, "both parked");

        drainOnAVirtualThread(() -> answerA.complete("yes"));
        assertEquals(List.of("A resumed: yes", "B resumed: from A", "drainer released", "request"), log);
        assertEquals(List.of(), reportedErrors);
    }

    @Test
    public void aSleepOnAVirtualDrainerKeepsTheSessionLock() throws InterruptedException {
        drainOnAVirtualThread(() -> UIFibers.runLater(() -> {
            log.add("before sleep");
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
            log.add("after sleep");
        }));
        assertEquals(List.of("before sleep", "after sleep", "drainer released", "request"), log);
        assertEquals(List.of(), reportedErrors);
    }

    @Test
    public void runUntilParkFromAVirtualThreadReturnsAtTheFirstPark() throws InterruptedException {
        final UI ui = UI.getCurrent();
        final CompletableFuture<String> answer = new CompletableFuture<>();
        LoomTests.withSessionLockReleased(() ->
                Thread.ofVirtual().start(() -> ui.accessSynchronously(() -> {
                    UIFibers.runUntilPark(() -> {
                        log.add("segment");
                        log.add("resumed: " + UIFibers.parkAndAwait(ui, answer));
                    });
                    log.add("runUntilPark returned");
                })).join());
        assertEquals(List.of("segment", "runUntilPark returned"), log);

        answer.complete("yes");
        MockVaadin.clientRoundtrip();
        assertEquals(List.of("segment", "runUntilPark returned", "resumed: yes"), log);
        assertEquals(List.of(), reportedErrors);
    }
}
