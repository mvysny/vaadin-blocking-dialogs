/*
 * Copyright 2026 Martin Vysny
 *
 * Licensed under the MIT License. See the LICENSE file in the project root
 * for the full license text.
 */
package com.github.mvysny.blockingdialogs;

import com.github.mvysny.blockingdialogs.uifiber.loom.MockVirtualThreadAwareServlet;
import com.github.mvysny.kaributesting.v10.MockBrowser;
import com.github.mvysny.kaributesting.v10.MockVaadin;
import com.github.mvysny.kaributesting.v10.Routes;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.confirmdialog.ConfirmDialog;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.server.ErrorEvent;
import com.vaadin.flow.server.ErrorHandler;
import com.vaadin.flow.server.VaadinRequest;
import com.vaadin.flow.server.VaadinSession;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

import static com.github.mvysny.kaributesting.v10.LocatorJ._assertOne;
import static com.github.mvysny.kaributesting.v10.LocatorJ._get;
import static com.github.mvysny.kaributesting.v10.pro.ConfirmDialogKt._fireConfirm;
import static com.github.mvysny.kaributesting.v10.pro.ConfirmDialogKt._fireReject;
import static org.junit.jupiter.api.Assertions.*;

/**
 * The runner-neutral half, on the real loom runner under Karibu: the test thread holds the session
 * lock and each roundtrip drains the access queue, mounting whichever UI fibers have woken; a UI fiber
 * really parks, and the test thread is free meanwhile.
 */
public class UIFibersTest {
    private static Routes routes;

    @BeforeAll
    public static void discoverRoutes() {
        routes = new Routes().autoDiscoverViews("com.github.mvysny.blockingdialogs");
    }

    /**
     * What the UI fibers of a test did, in order.
     */
    private final List<String> log = new ArrayList<>();

    /**
     * Everything handed to the session's error handler; pair with {@code clientRoundtrip(true)}.
     */
    private final List<Throwable> errors = new ArrayList<>();

    /**
     * The UI current when the error handler ran.
     */
    private final AtomicReference<UI> uiInHandler = new AtomicReference<>();

    @BeforeEach
    public void setupVaadin() {
        final MockVirtualThreadAwareServlet servlet = new MockVirtualThreadAwareServlet(routes);
        MockVaadin.setup(servlet.getUiFactory(), servlet);
        VaadinSession.getCurrent().setErrorHandler(event -> {
            errors.add(event.getThrowable());
            uiInHandler.set(UI.getCurrent());
        });
    }

    @AfterEach
    public void tearDownVaadin() {
        MockVaadin.tearDown();
    }

    @Nested
    public class Starting {
        @Test
        public void runLaterStartsAfterTheListenerReturns() {
            UIFibers.runLater(() -> log.add("UI fiber"));
            log.add("listener");
            MockVaadin.clientRoundtrip();
            assertEquals(List.of("listener", "UI fiber"), log);
        }

        @Test
        public void aUIFiberHasUIAndSessionButNoRequest() {
            final UI ui = UI.getCurrent();
            final AtomicReference<UI> uiSeen = new AtomicReference<>();
            final AtomicReference<VaadinSession> sessionSeen = new AtomicReference<>();
            final AtomicReference<VaadinRequest> requestSeen = new AtomicReference<>();
            UIFibers.runLater(() -> {
                uiSeen.set(UI.getCurrent());
                sessionSeen.set(VaadinSession.getCurrent());
                requestSeen.set(VaadinRequest.getCurrent());
            });
            MockVaadin.clientRoundtrip();
            assertSame(ui, uiSeen.get());
            assertSame(ui.getSession(), sessionSeen.get());
            assertNull(requestSeen.get(), "runLater promises no request, under every runner");
            assertSame(ui, UI.getCurrent(), "the caller keeps its own current instances");
        }

        @Test
        public void runLaterInsideAUIFiberStartsAtItsPark() {
            final CompletableFuture<String> answer = new CompletableFuture<>();
            UIFibers.runLater(() -> {
                log.add("A starts");
                UIFibers.runLater(() -> log.add("B runs"));
                log.add("A parks");
                UIFibers.parkAndAwait(UI.getCurrent(), answer);
                log.add("A resumes");
            });
            MockVaadin.clientRoundtrip();
            assertEquals(List.of("A starts", "A parks", "B runs"), log);
            answer.complete("x");
            MockVaadin.clientRoundtrip();
            assertEquals(List.of("A starts", "A parks", "B runs", "A resumes"), log);
        }

        /**
         * A done future wakes the UI fiber before it parks: it doesn't park at all, so the lock is never
         * released, and a UI fiber queued meanwhile waits for the next real park.
         */
        @Test
        public void aDoneFutureDoesNotPark() {
            UIFibers.runLater(() -> {
                UIFibers.runLater(() -> log.add("B runs"));
                log.add("A: " + UIFibers.parkAndAwait(UI.getCurrent(), CompletableFuture.completedFuture("done")));
            });
            MockVaadin.clientRoundtrip();
            assertEquals(List.of("A: done", "B runs"), log);
        }

        @Test
        public void runLaterWithoutUIThrows() {
            UI.setCurrent(null);
            assertThrows(IllegalStateException.class, () -> UIFibers.runLater(() -> {}));
        }

        @Test
        public void runLaterFromBackgroundThreadThrows() throws Exception {
            final UI ui = UI.getCurrent();
            final AtomicReference<Throwable> thrown = new AtomicReference<>();
            final Thread thread = new Thread(() -> {
                UI.setCurrent(ui);
                try {
                    UIFibers.runLater(() -> {});
                } catch (Throwable t) {
                    thrown.set(t);
                }
            });
            thread.start();
            thread.join();
            assertInstanceOf(IllegalStateException.class, thrown.get());
        }

        @Test
        public void parkOutsideUIFiberThrows() {
            assertFalse(UIFibers.isInUIFiber());
            assertThrows(IllegalStateException.class,
                    () -> UIFibers.parkAndAwait(UI.getCurrent(), new CompletableFuture<>()));
            assertThrows(IllegalStateException.class, UIFibers::checkInUIFiber);
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
            final ConfirmDialog dialog = confirmDialog();
            UIFibers.runUntilPark(() -> log.add("answer: " + BlockingDialogs.showAndAwait(dialog)));
            assertTrue(dialog.isOpened());
            assertEquals(List.of(), log);

            _fireConfirm(dialog);
            MockVaadin.clientRoundtrip();
            assertEquals(List.of("answer: CONFIRM"), log);
        }

        @Test
        public void returnsOnceTheUIFiberEnds() {
            UIFibers.runUntilPark(() -> log.add("UI fiber"));
            log.add("caller");
            assertEquals(List.of("UI fiber", "caller"), log);
        }

        @Test
        public void insideAUIFiberRunsInlineParksIncluded() {
            final CompletableFuture<String> answer = new CompletableFuture<>();
            UIFibers.runLater(() -> {
                log.add("A starts");
                UIFibers.runUntilPark(() -> log.add("B: " + UIFibers.parkAndAwait(UI.getCurrent(), answer)));
                log.add("A ends");
            });
            MockVaadin.clientRoundtrip();
            assertEquals(List.of("A starts"), log);
            answer.complete("resumed");
            MockVaadin.clientRoundtrip();
            assertEquals(List.of("A starts", "B: resumed", "A ends"), log);
        }

        /**
         * The inline UI fiber parks across F5 and resumes on the new UI; the calling UI fiber goes on
         * there too, not on the closed old UI.
         */
        @Test
        public void insideAUIFiberKeepsTheUIAParkReboundTo() {
            UI.getCurrent().navigate(TestViews.PreservedView.class);
            final AtomicReference<UI> inner = new AtomicReference<>();
            final AtomicReference<UI> outer = new AtomicReference<>();
            UIFibers.runLater(() -> {
                UIFibers.runUntilPark(() -> {
                    BlockingDialogs.showAndAwait(confirmDialog());
                    inner.set(UI.getCurrent());
                });
                outer.set(UI.getCurrent());
            });
            _assertOne(ConfirmDialog.class);
            final UI oldUI = UI.getCurrent();
            UI.getCurrent().getPage().reload();
            final UI newUI = UI.getCurrent();
            _fireConfirm(_get(ConfirmDialog.class));
            MockVaadin.clientRoundtrip();
            assertNotSame(oldUI, newUI);
            assertSame(newUI, inner.get());
            assertSame(newUI, outer.get());
        }

        @Test
        public void exceptionsGoToTheErrorHandlerInlineToo() {
            final RuntimeException outside = new RuntimeException("outside a UI fiber");
            UIFibers.runUntilPark(() -> {
                throw outside;
            });
            assertEquals(List.of(outside), errors);

            final RuntimeException inside = new RuntimeException("inside a UI fiber");
            UIFibers.runLater(() -> {
                UIFibers.runUntilPark(() -> {
                    throw inside;
                });
                log.add("A goes on");
            });
            MockVaadin.clientRoundtrip(true);
            assertEquals(List.of(outside, inside), errors);
            assertEquals(List.of("A goes on"), log);
        }

        /**
         * Logs the handler's failure: a UI fiber blocked on the contended {@code System.err} unmounts
         * under loom as on any IO (see {@code R_vt_unmount_releases_lock}), and resumes only at a later
         * roundtrip - stranded by the teardown otherwise, holding the stream's monitor.
         */
        @Test
        public void aThrowingErrorHandlerDoesNotEscape() throws InterruptedException {
            VaadinSession.getCurrent().setErrorHandler(new ThrowingErrorHandler());
            UIFibers.runLater(() -> {
                UIFibers.runUntilPark(() -> {
                    throw new RuntimeException("inside a UI fiber");
                });
                log.add("A goes on");
            });
            for (int i = 0; i < 500 && log.isEmpty(); i++) {
                MockVaadin.clientRoundtrip(true);
                Thread.sleep(10);
            }
            assertEquals(List.of("A goes on"), log);
        }
    }

    @Nested
    public class AccessSynchronously {
        @Test
        public void insideAUIFiberRunsInline() {
            UIFibers.runLater(() -> {
                final ConfirmDialogOutcome outcome = UIFibers.accessSynchronously(UI.getCurrent(),
                        () -> BlockingDialogs.showAndAwait(confirmDialog()));
                log.add("nested: " + outcome);
            });
            _fireReject(_get(ConfirmDialog.class));
            MockVaadin.clientRoundtrip();
            assertEquals(List.of("nested: REJECT"), log);
        }

        @Test
        public void outsideAUIFiberWithTheLockThrows() {
            assertThrows(IllegalStateException.class,
                    () -> UIFibers.accessSynchronously(UI.getCurrent(), () -> "x"));
        }

        @Test
        public void fromABackgroundThreadWaitsForTheUIFiberParksIncluded() throws Exception {
            final UI ui = UI.getCurrent();
            final AtomicReference<Object> result = new AtomicReference<>();
            final Thread job = new Thread(() -> result.set(UIFibers.accessSynchronously(ui,
                    () -> "the user said " + BlockingDialogs.showAndAwait(confirmDialog()))));
            job.start();
            awaitPendingAccess();
            _fireConfirm(_get(ConfirmDialog.class));
            MockVaadin.clientRoundtrip();
            job.join(5000);
            assertEquals("the user said CONFIRM", result.get());
        }

        @Test
        public void fromABackgroundThreadRethrowsToTheCaller() throws Exception {
            final UI ui = UI.getCurrent();
            final IllegalArgumentException boom = new IllegalArgumentException("boom");
            final AtomicReference<Throwable> thrown = new AtomicReference<>();
            final Thread job = new Thread(() -> {
                try {
                    UIFibers.accessSynchronously(ui, () -> {
                        throw boom;
                    });
                } catch (Throwable t) {
                    thrown.set(t);
                }
            });
            job.start();
            awaitPendingAccess();
            MockVaadin.clientRoundtrip(true);
            job.join(5000);
            assertSame(boom, thrown.get());
            assertEquals(List.of(), errors);
        }
    }

    @Nested
    public class Lifetime {
        @Test
        public void anchorDetachCancelsTheWaitQuietly() {
            final Div anchor = new Div();
            _get(TestViews.StartView.class).add(anchor);
            final CompletableFuture<String> future = new CompletableFuture<>();
            UIFibers.runLater(() -> {
                try {
                    UIFibers.parkAndAwait(anchor, future);
                    log.add("resumed");
                } finally {
                    log.add("finally");
                }
            });
            MockVaadin.clientRoundtrip();
            UI.getCurrent().navigate(TestViews.OtherView.class);
            MockVaadin.clientRoundtrip(true);
            MockVaadin.clientRoundtrip(true);
            assertEquals(List.of("finally"), log);
            assertTrue(future.isCancelled(), "a background job sees the wait is gone");
            assertEquals(List.of(), errors);
        }

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
            assertEquals(List.of(), errors);
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
            assertEquals(List.of(), errors);
        }

        @Test
        public void preserveOnRefreshKeepsAViewsWaitAndResumesOnTheNewUI() {
            UI.getCurrent().navigate(TestViews.PreservedView.class);
            final TestViews.PreservedView view = _get(TestViews.PreservedView.class);
            final UI oldUI = UI.getCurrent();
            final CompletableFuture<String> answer = new CompletableFuture<>();
            final AtomicReference<UI> uiAfterPark = new AtomicReference<>();
            UIFibers.runLater(() -> {
                log.add(UIFibers.parkAndAwait(view, answer));
                uiAfterPark.set(UI.getCurrent());
            });
            MockVaadin.clientRoundtrip();

            UI.getCurrent().getPage().reload();
            MockVaadin.clientRoundtrip();  // the new UI's response: the deferred check finds the view attached
            assertFalse(answer.isCancelled());
            answer.complete("still here");
            MockVaadin.clientRoundtrip();

            assertEquals(List.of("still here"), log);
            assertNotSame(oldUI, view.getUI().orElseThrow());
            assertSame(view.getUI().orElseThrow(), uiAfterPark.get(), "the UI fiber follows its anchor to the new UI");
            assertEquals(List.of(), errors);
        }

        /**
         * A UI fiber in the current UI waiting on a confirm dialog, logging its {@code finally}.
         */
        private void startConfirmUIFiber() {
            UIFibers.runLater(() -> {
                try {
                    log.add("answer: " + BlockingDialogs.showAndAwait(confirmDialog()));
                } finally {
                    log.add("finally");
                }
            });
        }
    }

    @Nested
    public class Exceptions {
        @Test
        public void escapingExceptionGoesToErrorHandlerWithUICurrent() {
            final UI ui = UI.getCurrent();
            final RuntimeException boom = new RuntimeException("boom");
            UIFibers.runLater(() -> {
                throw boom;
            });
            MockVaadin.clientRoundtrip(true);
            assertEquals(List.of(boom), errors);
            assertSame(ui, uiInHandler.get());
        }

        @Test
        public void failedFutureRethrowsItsCauseAsIs() {
            final IOException diskFull = new IOException("disk full");
            final CompletableFuture<String> future = new CompletableFuture<>();
            final AtomicReference<Throwable> caught = startCatching(future);
            future.completeExceptionally(diskFull);
            MockVaadin.clientRoundtrip();
            assertSame(diskFull, caught.get());
        }

        @Test
        public void failedDependentStageRethrowsItsCauseAsIs() {
            final IllegalArgumentException boom = new IllegalArgumentException("boom");
            final CompletableFuture<String> source = new CompletableFuture<>();
            final AtomicReference<Throwable> caught = startCatching(source.thenApply(s -> {
                throw boom;
            }));
            source.complete("x");
            MockVaadin.clientRoundtrip();
            assertSame(boom, caught.get());
        }

        @Test
        public void futureFailedByABackgroundThreadWakesTheUIFiber() throws InterruptedException {
            final IOException diskFull = new IOException("disk full");
            final CompletableFuture<String> future = new CompletableFuture<>();
            final AtomicReference<Throwable> caught = startCatching(future);
            final Thread job = new Thread(() -> future.completeExceptionally(diskFull));
            job.start();
            job.join();
            MockVaadin.clientRoundtrip();
            assertSame(diskFull, caught.get());
        }

        @Test
        public void cancelledFutureThrowsCancellationException() {
            final CompletableFuture<String> future = new CompletableFuture<>();
            final AtomicReference<Throwable> caught = startCatching(future);
            future.cancel(false);
            MockVaadin.clientRoundtrip();
            assertInstanceOf(CancellationException.class, caught.get());
        }

        @Test
        public void interruptedParkThrowsCancellationExceptionWithTheFlagRestored() {
            final AtomicReference<Thread> fiber = new AtomicReference<>();
            final AtomicReference<Throwable> caught = new AtomicReference<>();
            final AtomicReference<Boolean> interrupted = new AtomicReference<>();
            UIFibers.runLater(() -> {
                fiber.set(Thread.currentThread());
                try {
                    UIFibers.parkAndAwait(UI.getCurrent(), new CompletableFuture<>());
                } catch (CancellationException e) {
                    caught.set(e);
                    interrupted.set(Thread.currentThread().isInterrupted());
                }
            });
            MockVaadin.clientRoundtrip();
            fiber.get().interrupt();
            MockVaadin.clientRoundtrip();
            assertInstanceOf(CancellationException.class, caught.get());
            assertTrue(interrupted.get());
        }

        /**
         * A UI fiber parked on {@code future}, catching what the park throws.
         */
        private AtomicReference<Throwable> startCatching(CompletableFuture<String> future) {
            final AtomicReference<Throwable> caught = new AtomicReference<>();
            UIFibers.runLater(() -> {
                try {
                    UIFibers.parkAndAwait(UI.getCurrent(), future);
                } catch (Exception e) {
                    caught.set(e);
                }
            });
            MockVaadin.clientRoundtrip();
            assertNull(caught.get(), "parked");
            return caught;
        }
    }

    /**
     * Waits until a background thread has queued a {@link UI#access} task.
     */
    private static void awaitPendingAccess() throws InterruptedException {
        final long deadline = System.currentTimeMillis() + 5000;
        while (VaadinSession.getCurrent().getPendingAccessQueue().isEmpty()) {
            if (System.currentTimeMillis() > deadline) {
                fail("The background thread queued no access task");
            }
            Thread.sleep(5);
        }
    }

    private static final class ThrowingErrorHandler implements ErrorHandler {
        @Override
        public void error(ErrorEvent event) {
            throw new IllegalStateException("the error handler failed");
        }
    }

    private static ConfirmDialog confirmDialog() {
        final ConfirmDialog dialog = new ConfirmDialog();
        dialog.setText("Sure?");
        dialog.setRejectable(true);
        dialog.setCancelable(true);
        return dialog;
    }
}
