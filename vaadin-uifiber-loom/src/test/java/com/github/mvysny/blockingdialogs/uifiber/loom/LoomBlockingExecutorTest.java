/*
 * Copyright 2026 Martin Vysny
 *
 * Licensed under the MIT License. See the LICENSE file in the project root
 * for the full license text.
 */
package com.github.mvysny.blockingdialogs.uifiber.loom;

import com.github.mvysny.blockingdialogs.BlockingDialogs;
import com.github.mvysny.blockingdialogs.BlockingExecutor;
import com.github.mvysny.blockingdialogs.ConfirmDialogOutcome;
import com.github.mvysny.kaributesting.v10.MockBrowser;
import com.github.mvysny.kaributesting.v10.MockVaadin;
import com.github.mvysny.kaributesting.v10.Routes;
import com.github.mvysny.kaributesting.v10.mock.MockedUI;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.confirmdialog.ConfirmDialog;
import com.vaadin.flow.server.VaadinRequest;
import com.vaadin.flow.server.VaadinSession;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledForJreRange;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static com.github.mvysny.kaributesting.v10.LocatorJ._assertNone;
import static com.github.mvysny.kaributesting.v10.LocatorJ._assertOne;
import static com.github.mvysny.kaributesting.v10.LocatorJ._get;
import static com.github.mvysny.kaributesting.v10.pro.ConfirmDialogKt._fireCancel;
import static com.github.mvysny.kaributesting.v10.pro.ConfirmDialogKt._fireConfirm;
import static org.junit.jupiter.api.Assertions.*;

/**
 * The loom strategy under Karibu. The test thread plays the carrier: it holds the session lock, and
 * each roundtrip drains the access queue, mounting whichever UI fibers have a continuation queued. So
 * unlike the scripted strategy, a UI fiber really parks, and the test thread is free meanwhile.
 */
public class LoomBlockingExecutorTest {
    private static Routes routes;

    /**
     * What the UI fibers of a test did, in order.
     */
    private final List<String> log = new ArrayList<>();

    /**
     * Everything handed to the session's error handler.
     */
    private List<Throwable> reportedErrors;

    @BeforeAll
    public static void discoverRoutes() {
        routes = new Routes().autoDiscoverViews("com.github.mvysny.blockingdialogs.uifiber.loom");
    }

    @BeforeEach
    public void setupVaadin() {
        MockVaadin.setup(MockedUI::new, new MockVirtualThreadAwareServlet(routes));
        reportedErrors = new ArrayList<>();
        VaadinSession.getCurrent().setErrorHandler(event -> reportedErrors.add(event.getThrowable()));
    }

    @AfterEach
    public void tearDownVaadin() {
        MockVaadin.tearDown();
    }

    @Test
    public void isTheStrategyOnTheClasspath() {
        assertInstanceOf(LoomBlockingExecutor.class, BlockingExecutor.get());
    }

    @Nested
    public class Wiring {
        @Test
        public void refusesAnUnwrappedSessionLock() {
            MockVaadin.tearDown();
            MockVaadin.setup(routes);
            final IllegalStateException e = assertThrows(IllegalStateException.class, () -> BlockingDialogs.runLater(() -> {}));
            assertTrue(e.getMessage().contains("LoomVaadinServlet"), e.getMessage());
        }

        @Test
        public void refusesAPinningJdkUnlessAllowed() {
            final IllegalStateException e = assertThrows(IllegalStateException.class, () -> LoomBlockingExecutor.checkJdk(21, false));
            assertTrue(e.getMessage().contains("JEP 491"), e.getMessage());
            assertTrue(e.getMessage().contains("-D" + LoomBlockingExecutor.ALLOW_PINNING_JDK + "=true"), e.getMessage());
            LoomBlockingExecutor.checkJdk(21, true);
            LoomBlockingExecutor.checkJdk(24, false);
        }
    }

    @Nested
    public class UIFibers {
        @Test
        public void runInAVirtualThreadWithUIAndSessionButNoRequest() {
            final UI ui = UI.getCurrent();
            final AtomicReference<Thread> thread = new AtomicReference<>();
            final AtomicReference<UI> uiSeen = new AtomicReference<>();
            final AtomicReference<VaadinSession> sessionSeen = new AtomicReference<>();
            final AtomicReference<VaadinRequest> requestSeen = new AtomicReference<>();
            BlockingDialogs.runLater(() -> {
                thread.set(Thread.currentThread());
                uiSeen.set(UI.getCurrent());
                sessionSeen.set(VaadinSession.getCurrent());
                requestSeen.set(VaadinRequest.getCurrent());
            });
            MockVaadin.clientRoundtrip();

            assertTrue(thread.get().isVirtual());
            assertSame(ui, uiSeen.get());
            assertSame(ui.getSession(), sessionSeen.get());
            assertNull(requestSeen.get(), "BlockingExecutor.runLater promises no request, under every strategy");
            assertSame(ui, UI.getCurrent(), "the UI fiber's current instances are its own thread's");
            assertEquals(List.of(), reportedErrors);
        }

        @Test
        public void escapingExceptionGoesToTheErrorHandler() {
            final RuntimeException failure = new RuntimeException("simulated");
            BlockingDialogs.runLater(() -> {
                throw failure;
            });
            // `true` keeps our own error handler instead of Karibu's fail-the-test one
            MockVaadin.clientRoundtrip(true);
            assertEquals(List.of(failure), reportedErrors);
        }

        /**
         * vaadin-loom's demo: two nested confirms, one answer each.
         */
        @Test
        public void nestedDialogs() {
            BlockingDialogs.runLater(() -> {
                if (BlockingDialogs.showAndAwait(confirmDialog("Are you sure?")) == ConfirmDialogOutcome.CONFIRM) {
                    log.add("second: " + BlockingDialogs.showAndAwait(confirmDialog("Are you really sure?")));
                }
            });
            _fireConfirm(_get(ConfirmDialog.class));
            _fireCancel(_get(ConfirmDialog.class));
            _assertNone(ConfirmDialog.class);
            assertEquals(List.of("second: CANCEL"), log);
            assertEquals(List.of(), reportedErrors);
        }

        @Test
        public void runLaterInsideAUIFiberStartsAtItsPark() {
            final CompletableFuture<String> answer = new CompletableFuture<>();
            BlockingDialogs.runLater(() -> {
                log.add("A starts");
                BlockingDialogs.runLater(() -> log.add("B runs"));
                log.add("A parks");
                BlockingDialogs.parkAndAwait(UI.getCurrent(), answer);
                log.add("A resumes");
            });
            MockVaadin.clientRoundtrip();
            assertEquals(List.of("A starts", "A parks", "B runs"), log);
            answer.complete("x");
            MockVaadin.clientRoundtrip();
            assertEquals(List.of("A starts", "A parks", "B runs", "A resumes"), log);
        }

        /**
         * On JDK 21-23 the virtual thread pins and parks its carrier instead - the test thread,
         * holding the session lock: {@code clientRoundtrip()} would never return, hence the gate. See
         * {@code R_vt_pinning}.
         */
        @Test
        @EnabledForJreRange(minVersion = 24, disabledReason = "Parking inside synchronized pins the carrier before JEP 491 (JDK 24)")
        public void parkingInsideSynchronizedUnmounts() {
            final Object monitor = new Object();
            final CompletableFuture<String> answer = new CompletableFuture<>();
            BlockingDialogs.runLater(() -> {
                synchronized (monitor) {
                    log.add(BlockingDialogs.parkAndAwait(UI.getCurrent(), answer));
                }
            });
            MockVaadin.clientRoundtrip();
            assertEquals(List.of(), log, "the UI fiber must be parked inside the synchronized block");

            answer.complete("resumed");
            MockVaadin.clientRoundtrip();
            assertEquals(List.of("resumed"), log);
            assertEquals(List.of(), reportedErrors);
        }
    }

    /**
     * No roundtrip between the call and the asserts: what the UI fiber did is there when the call
     * returns.
     */
    @Nested
    public class RunUntilPark {
        @Test
        public void returnsOnceTheUIFiberParks() {
            final ConfirmDialog dialog = confirmDialog("Sure?");
            BlockingDialogs.runUntilPark(() -> log.add("answer: " + BlockingDialogs.showAndAwait(dialog)));
            assertTrue(dialog.isOpened());
            assertEquals(List.of(), log);

            _fireConfirm(dialog);
            MockVaadin.clientRoundtrip();
            assertEquals(List.of("answer: CONFIRM"), log);
            assertEquals(List.of(), reportedErrors);
        }

        @Test
        public void returnsOnceTheUIFiberEndsInAVirtualThreadOfItsOwn() {
            final UI ui = UI.getCurrent();
            final AtomicReference<Thread> thread = new AtomicReference<>();
            BlockingDialogs.runUntilPark(() -> {
                thread.set(Thread.currentThread());
                log.add("UI fiber");
            });
            log.add("caller");
            assertEquals(List.of("UI fiber", "caller"), log);
            assertTrue(thread.get().isVirtual());
            assertSame(ui, UI.getCurrent(), "the UI fiber's current instances are its own thread's");
        }

        @Test
        public void runsNoAccessTaskQueuedEarlier() {
            UI.getCurrent().access(() -> log.add("earlier task"));
            BlockingDialogs.runUntilPark(() -> log.add("UI fiber"));
            assertEquals(List.of("UI fiber"), log);
            MockVaadin.clientRoundtrip();
            assertEquals(List.of("UI fiber", "earlier task"), log);
        }

        @Test
        public void insideAUIFiberRunsInlineParksIncluded() {
            final CompletableFuture<String> answer = new CompletableFuture<>();
            BlockingDialogs.runLater(() -> {
                log.add("A starts");
                BlockingDialogs.runUntilPark(() -> log.add("B: " + BlockingDialogs.parkAndAwait(UI.getCurrent(), answer)));
                log.add("A ends");
            });
            MockVaadin.clientRoundtrip();
            assertEquals(List.of("A starts"), log);
            answer.complete("resumed");
            MockVaadin.clientRoundtrip();
            assertEquals(List.of("A starts", "B: resumed", "A ends"), log);
            assertEquals(List.of(), reportedErrors);
        }

        @Test
        public void exceptionsGoToTheErrorHandlerInlineToo() {
            final RuntimeException outside = new RuntimeException("outside a UI fiber");
            BlockingDialogs.runUntilPark(() -> {
                throw outside;
            });
            assertEquals(List.of(outside), reportedErrors);

            final RuntimeException inside = new RuntimeException("inside a UI fiber");
            BlockingDialogs.runLater(() -> {
                BlockingDialogs.runUntilPark(() -> {
                    throw inside;
                });
                log.add("A goes on");
            });
            // `true` keeps our own error handler instead of Karibu's fail-the-test one
            MockVaadin.clientRoundtrip(true);
            assertEquals(List.of(outside, inside), reportedErrors);
            assertEquals(List.of("A goes on"), log);
        }

        /**
         * A virtual caller can't carry the UI fiber, so a platform thread does while the caller waits.
         */
        @Test
        public void runsOnAVirtualThreadOutsideAUIFiber() throws InterruptedException {
            final UI ui = UI.getCurrent();
            final VaadinSession session = VaadinSession.getCurrent();
            final AtomicReference<Throwable> thrown = new AtomicReference<>();
            session.unlock();
            try {
                Thread.ofVirtual().start(() -> ui.accessSynchronously(() -> {
                    try {
                        BlockingDialogs.runUntilPark(() -> log.add("UI fiber"));
                        log.add("returned");
                    } catch (Throwable t) {
                        thrown.set(t);
                    }
                })).join();
            } finally {
                session.lock();
            }
            assertNull(thrown.get());
            assertEquals(List.of("UI fiber", "returned"), log);
        }
    }

    /**
     * The thread that completes the future is the one that queues the UI fiber's next continuation.
     */
    @Nested
    public class Resuming {
        @Test
        public void byAnotherUIFiber() {
            final CompletableFuture<String> answer = new CompletableFuture<>();
            BlockingDialogs.runLater(() -> log.add(BlockingDialogs.parkAndAwait(UI.getCurrent(), answer)));
            MockVaadin.clientRoundtrip();
            BlockingDialogs.runLater(() -> answer.complete("hello"));
            MockVaadin.clientRoundtrip();
            MockVaadin.clientRoundtrip();
            assertEquals(List.of("hello"), log);
            assertEquals(List.of(), reportedErrors);
        }

        /**
         * Anything handed out by {@code Executors.newVirtualThreadPerTaskExecutor()}; the carrier
         * mustn't reject a virtual caller.
         */
        @Test
        public void byABackgroundVirtualThreadWhileTheLockIsHeld() throws InterruptedException {
            final CompletableFuture<String> answer = new CompletableFuture<>();
            BlockingDialogs.runLater(() -> log.add(BlockingDialogs.parkAndAwait(UI.getCurrent(), answer)));
            MockVaadin.clientRoundtrip();
            Thread.ofVirtual().start(() -> answer.complete("hello")).join();
            MockVaadin.clientRoundtrip();
            assertEquals(List.of("hello"), log);
            assertEquals(List.of(), reportedErrors);
        }

        /**
         * With the lock free, the completing virtual thread drains the access queue itself, and a
         * continuation can't mount on it: it goes to a platform thread instead.
         */
        @Test
        public void byABackgroundVirtualThreadWhileTheLockIsFree() throws InterruptedException {
            final VaadinSession session = VaadinSession.getCurrent();
            final CompletableFuture<String> answer = new CompletableFuture<>();
            final CompletableFuture<String> resumed = new CompletableFuture<>();
            BlockingDialogs.runLater(() -> resumed.complete(BlockingDialogs.parkAndAwait(UI.getCurrent(), answer)));
            MockVaadin.clientRoundtrip();

            session.unlock();
            try {
                Thread.ofVirtual().start(() -> answer.complete("hello")).join();
                assertEquals("hello", resumed.get(5, TimeUnit.SECONDS));
            } catch (Exception e) {
                fail(e);
            } finally {
                session.lock();
            }
            assertEquals(List.of(), reportedErrors);
        }
    }

    /**
     * The anchor's death as only a real park shows it: Vaadin forbids a session destroy inside an
     * access task, which is where the scripted strategy's park runs.
     */
    @Nested
    public class Lifetime {
        /**
         * Karibu's session close, which runs {@code fireSessionDestroy} and then plays the browser's
         * reload into a fresh session. Not {@code fireSessionDestroy} on an open session: its
         * {@code session.close()} would swap the session inside the destroy task.
         */
        @Test
        public void sessionDestroyCancelsTheWaitAtOnce() {
            final VaadinSession session = VaadinSession.getCurrent();
            startConfirmUIFiber();
            _assertOne(ConfirmDialog.class);

            session.close();

            assertEquals(List.of("finally"), log);
            assertEquals(List.of(), reportedErrors);
            assertNotSame(session, VaadinSession.getCurrent());
        }

        @Test
        public void tabCloseCancelsTheWaitAtOnce() {
            final String first = MockBrowser.getCurrentWindowName();
            MockBrowser.newTab();
            final String second = MockBrowser.getCurrentWindowName();
            startConfirmUIFiber();
            _assertOne(ConfirmDialog.class);

            MockBrowser.switchTo(first);
            MockBrowser.closeTab(second);
            MockVaadin.clientRoundtrip(true);

            assertEquals(List.of("finally"), log);
            assertEquals(List.of(), reportedErrors);
        }

        @Test
        public void preserveOnRefreshKeepsADialogsWaitAndResumesOnTheNewUI() {
            UI.getCurrent().navigate(TestViews.PreservedView.class);
            final UI oldUI = UI.getCurrent();
            final AtomicReference<UI> uiAfterPark = new AtomicReference<>();
            BlockingDialogs.runLater(() -> {
                log.add("answer: " + BlockingDialogs.showAndAwait(confirmDialog("Sure?")));
                uiAfterPark.set(UI.getCurrent());
            });
            _assertOne(ConfirmDialog.class);

            UI.getCurrent().getPage().reload();
            _fireConfirm(_get(ConfirmDialog.class));
            MockVaadin.clientRoundtrip();

            assertEquals(List.of("answer: CONFIRM"), log);
            assertNotSame(oldUI, UI.getCurrent());
            assertSame(UI.getCurrent(), uiAfterPark.get(), "the UI fiber follows its dialog to the new UI");
            assertEquals(List.of(), reportedErrors);
        }

        @Test
        public void preserveOnRefreshKeepsAViewsWaitAndResumesOnTheNewUI() {
            UI.getCurrent().navigate(TestViews.PreservedView.class);
            final TestViews.PreservedView view = _get(TestViews.PreservedView.class);
            final CompletableFuture<String> answer = new CompletableFuture<>();
            final AtomicReference<UI> uiAfterPark = new AtomicReference<>();
            BlockingDialogs.runLater(() -> {
                log.add(BlockingDialogs.parkAndAwait(view, answer));
                uiAfterPark.set(UI.getCurrent());
            });
            MockVaadin.clientRoundtrip();

            UI.getCurrent().getPage().reload();
            MockVaadin.clientRoundtrip();  // the new UI's response: the deferred check finds the view attached
            assertFalse(answer.isCancelled());
            answer.complete("still here");
            MockVaadin.clientRoundtrip();

            assertEquals(List.of("still here"), log);
            assertSame(view.getUI().orElseThrow(), uiAfterPark.get(), "the UI fiber follows its anchor to the new UI");
            assertEquals(List.of(), reportedErrors);
        }

        /**
         * A UI fiber in the current UI waiting on a confirm dialog, logging its {@code finally}.
         */
        private void startConfirmUIFiber() {
            BlockingDialogs.runLater(() -> {
                try {
                    log.add("answer: " + BlockingDialogs.showAndAwait(confirmDialog("Sure?")));
                } finally {
                    log.add("finally");
                }
            });
        }
    }

    private static ConfirmDialog confirmDialog(String text) {
        final ConfirmDialog dialog = new ConfirmDialog();
        dialog.setText(text);
        dialog.setCancelable(true);
        return dialog;
    }
}
