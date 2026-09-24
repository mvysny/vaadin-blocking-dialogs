/*
 * Copyright 2026 Martin Vysny
 *
 * Licensed under the MIT License. See the LICENSE file in the project root
 * for the full license text.
 */
package com.github.mvysny.blockingdialogs;

import com.github.mvysny.kaributesting.v10.MockVaadin;
import com.github.mvysny.kaributesting.v10.Routes;
import com.vaadin.flow.component.ComponentUtil;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.confirmdialog.ConfirmDialog;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.server.VaadinSession;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

import static com.github.mvysny.kaributesting.v10.LocatorJ._assertNone;
import static com.github.mvysny.kaributesting.v10.LocatorJ._assertOne;
import static com.github.mvysny.kaributesting.v10.LocatorJ._click;
import static com.github.mvysny.kaributesting.v10.LocatorJ._get;
import static com.github.mvysny.kaributesting.v10.LocatorJ._setValue;
import static com.github.mvysny.kaributesting.v10.pro.ConfirmDialogKt._fireCancel;
import static com.github.mvysny.kaributesting.v10.pro.ConfirmDialogKt._fireConfirm;
import static com.github.mvysny.kaributesting.v10.pro.ConfirmDialogKt._fireReject;
import static org.junit.jupiter.api.Assertions.*;

/**
 * The strategy-neutral half, through {@link ScriptedBlockingExecutor}: it parks nothing, so these
 * tests pin what every strategy shares - routing, the helpers, the lifetime, the exception contract.
 */
public class BlockingDialogsTest {
    private static Routes routes;

    @BeforeAll
    public static void discoverRoutes() {
        routes = new Routes().autoDiscoverViews("com.github.mvysny.blockingdialogs");
    }

    /**
     * What the UI fibers of a test did, in order.
     */
    private final List<String> log = new ArrayList<>();

    @BeforeEach
    public void setupVaadin() {
        MockVaadin.setup(routes);
        ScriptedBlockingExecutor.setup();
    }

    @AfterEach
    public void tearDownVaadin() {
        try {
            ScriptedBlockingExecutor.tearDown();
        } finally {
            MockVaadin.tearDown();
        }
    }

    @Nested
    public class ConfirmDialogHelper {
        @Test
        public void returnsTheAnswer() {
            for (ConfirmDialogOutcome answer : ConfirmDialogOutcome.values()) {
                ScriptedBlockingExecutor.user.add(() -> {
                    final ConfirmDialog dialog = _get(ConfirmDialog.class);
                    switch (answer) {
                        case CONFIRM -> _fireConfirm(dialog);
                        case REJECT -> _fireReject(dialog);
                        case CANCEL -> _fireCancel(dialog);
                    }
                });
                BlockingDialogs.runLater(() -> log.add("answer: " + BlockingDialogs.showAndAwait(confirmDialog())));
                ScriptedBlockingExecutor.endRequest();
                _assertNone(ConfirmDialog.class);
            }
            assertEquals(List.of("answer: CONFIRM", "answer: REJECT", "answer: CANCEL"), log);
        }

        @Test
        public void closedWithoutAnswerIsCancel() {
            // Escape with the Cancel button hidden: the dialog closes, no CancelEvent
            ScriptedBlockingExecutor.user.add(() -> {
                final ConfirmDialog dialog = _get(ConfirmDialog.class);
                dialog.close();
                ComponentUtil.fireEvent(dialog, new ConfirmDialog.ClosedEvent(dialog, true));
            });
            BlockingDialogs.runLater(() -> log.add("answer: " + BlockingDialogs.showAndAwait(confirmDialog())));
            ScriptedBlockingExecutor.endRequest();
            assertEquals(List.of("answer: CANCEL"), log);
        }

        @Test
        public void sameDialogShownTwice() {
            final ConfirmDialog dialog = confirmDialog();
            ScriptedBlockingExecutor.user.add(() -> _fireConfirm(_get(ConfirmDialog.class)));
            ScriptedBlockingExecutor.user.add(() -> _fireReject(_get(ConfirmDialog.class)));
            BlockingDialogs.runLater(() -> {
                log.add("first: " + BlockingDialogs.showAndAwait(dialog));
                log.add("second: " + BlockingDialogs.showAndAwait(dialog));
            });
            ScriptedBlockingExecutor.endRequest();
            assertEquals(List.of("first: CONFIRM", "second: REJECT"), log);
        }

        @Test
        public void serializableWhileWaiting() {
            ScriptedBlockingExecutor.user.add(() -> {
                final ConfirmDialog dialog = _get(ConfirmDialog.class);
                assertDoesNotThrow(() -> serialize(dialog));
                _fireConfirm(dialog);
            });
            BlockingDialogs.runLater(() -> log.add("answer: " + BlockingDialogs.showAndAwait(confirmDialog())));
            ScriptedBlockingExecutor.endRequest();
            assertEquals(List.of("answer: CONFIRM"), log);
        }
    }

    @Nested
    public class DialogHelper {
        @Test
        public void returnsTheAnswer() {
            ScriptedBlockingExecutor.user.add(() -> {
                _setValue(_get(TextField.class, spec -> spec.withLabel("File name")), "notes.txt");
                _click(_get(Button.class, spec -> spec.withText("Save")));
            });
            BlockingDialogs.runLater(() -> {
                final CompletableFuture<String> name = new CompletableFuture<>();
                final TextField field = new TextField("File name");
                final Dialog dialog = new Dialog(field, new Button("Save", e -> name.complete(field.getValue())));
                log.add("name: " + BlockingDialogs.showAndAwait(dialog, name));
            });
            ScriptedBlockingExecutor.endRequest();
            assertEquals(List.of("name: notes.txt"), log);
            _assertNone(Dialog.class);
        }

        @Test
        public void closesTheDialogWhenTheWaitDies() {
            final AtomicReference<Dialog> dialog = new AtomicReference<>();
            ScriptedBlockingExecutor.user.add(() -> {
                _assertOne(Dialog.class);
                dialog.get().removeFromParent();  // the dialog dies unanswered
            });
            BlockingDialogs.runLater(() -> {
                dialog.set(new Dialog());
                try {
                    BlockingDialogs.showAndAwait(dialog.get(), new CompletableFuture<>());
                    log.add("answered");
                } finally {
                    log.add("finally");
                }
            });
            ScriptedBlockingExecutor.endRequest();
            assertEquals(List.of("finally"), log);
            assertFalse(dialog.get().isOpened());
        }
    }

    @Nested
    public class Lifetime {
        @Test
        public void anchorDetachCancelsTheWaitQuietly() {
            final List<Throwable> errors = collectErrors();
            final Div anchor = new Div();
            _get(TestViews.StartView.class).add(anchor);
            ScriptedBlockingExecutor.user.add(() -> UI.getCurrent().navigate(TestViews.OtherView.class));
            BlockingDialogs.runLater(() -> {
                try {
                    BlockingDialogs.parkAndAwait(anchor, new CompletableFuture<>());
                    log.add("resumed");
                } finally {
                    log.add("finally");
                }
            });
            MockVaadin.runUIQueue(true);
            assertEquals(List.of("finally"), log);
            assertEquals(List.of(), errors);
        }

        @Test
        public void preserveOnRefreshMigrationKeepsTheWait() {
            UI.getCurrent().navigate(TestViews.PreservedView.class);
            final TestViews.PreservedView view = _get(TestViews.PreservedView.class);
            final UI oldUI = UI.getCurrent();
            final CompletableFuture<String> future = new CompletableFuture<>();
            ScriptedBlockingExecutor.user.add(() -> {
                UI.getCurrent().getPage().reload();
                MockVaadin.clientRoundtrip();  // the new UI's response: the deferred check finds the view attached
                assertFalse(future.isCancelled());
                future.complete("still here");
            });
            final AtomicReference<UI> uiAfterPark = new AtomicReference<>();
            BlockingDialogs.runLater(() -> {
                log.add(BlockingDialogs.parkAndAwait(view, future));
                uiAfterPark.set(UI.getCurrent());
            });
            ScriptedBlockingExecutor.endRequest();
            assertEquals(List.of("still here"), log);
            assertNotSame(oldUI, view.getUI().orElseThrow());
            assertSame(view.getUI().orElseThrow(), uiAfterPark.get(), "the UI fiber follows its anchor to the new UI");
        }

        @Test
        public void preserveOnRefreshMigrationKeepsADialogsWait() {
            UI.getCurrent().navigate(TestViews.PreservedView.class);
            ScriptedBlockingExecutor.user.add(() -> {
                UI.getCurrent().getPage().reload();
                MockVaadin.clientRoundtrip();
                _fireConfirm(_get(ConfirmDialog.class));
            });
            final AtomicReference<UI> uiAfterPark = new AtomicReference<>();
            BlockingDialogs.runLater(() -> {
                log.add("answer: " + BlockingDialogs.showAndAwait(confirmDialog()));
                uiAfterPark.set(UI.getCurrent());
            });
            ScriptedBlockingExecutor.endRequest();
            assertEquals(List.of("answer: CONFIRM"), log);
            assertSame(UI.getCurrent(), uiAfterPark.get(), "the UI fiber follows its dialog to the new UI");
        }
    }

    @Nested
    public class Exceptions {
        @Test
        public void escapingExceptionGoesToErrorHandlerWithUICurrent() {
            final List<Throwable> errors = new ArrayList<>();
            final UI ui = UI.getCurrent();
            final AtomicReference<UI> uiInHandler = new AtomicReference<>();
            VaadinSession.getCurrent().setErrorHandler(new ErrorHandlerSpy(errors, uiInHandler));
            final RuntimeException boom = new RuntimeException("boom");
            BlockingDialogs.runLater(() -> {
                throw boom;
            });
            MockVaadin.runUIQueue(true);
            assertEquals(List.of(boom), errors);
            assertSame(ui, uiInHandler.get());
        }

        @Test
        public void failedFutureRethrowsItsCauseAsIs() {
            final IOException diskFull = new IOException("disk full");
            final CompletableFuture<String> future = new CompletableFuture<>();
            final AtomicReference<Throwable> caught = new AtomicReference<>();
            ScriptedBlockingExecutor.user.add(() -> future.completeExceptionally(diskFull));
            BlockingDialogs.runLater(() -> {
                try {
                    BlockingDialogs.parkAndAwait(UI.getCurrent(), future);
                } catch (Exception e) {
                    caught.set(e);
                }
            });
            ScriptedBlockingExecutor.endRequest();
            assertSame(diskFull, caught.get());
        }

        @Test
        public void cancelledFutureThrowsCancellationException() {
            final CompletableFuture<String> future = new CompletableFuture<>();
            final AtomicReference<Throwable> caught = new AtomicReference<>();
            ScriptedBlockingExecutor.user.add(() -> future.cancel(false));
            BlockingDialogs.runLater(() -> {
                try {
                    BlockingDialogs.parkAndAwait(UI.getCurrent(), future);
                } catch (CancellationException e) {
                    caught.set(e);
                }
            });
            ScriptedBlockingExecutor.endRequest();
            assertInstanceOf(CancellationException.class, caught.get());
        }

        @Test
        public void interruptedParkThrowsCancellationExceptionWithTheFlagRestored() {
            final AtomicReference<Throwable> caught = new AtomicReference<>();
            final AtomicReference<Boolean> interrupted = new AtomicReference<>();
            ScriptedBlockingExecutor.user.add(() -> Thread.currentThread().interrupt());
            BlockingDialogs.runLater(() -> {
                try {
                    BlockingDialogs.parkAndAwait(UI.getCurrent(), new CompletableFuture<>());
                } catch (CancellationException e) {
                    caught.set(e);
                    interrupted.set(Thread.interrupted());  // clears it: the UI fiber runs on the test thread
                }
            });
            ScriptedBlockingExecutor.endRequest();
            assertInstanceOf(CancellationException.class, caught.get());
            assertTrue(interrupted.get());
        }
    }

    @Nested
    public class Threading {
        @Test
        public void runLaterWithoutUIThrows() {
            UI.setCurrent(null);
            assertThrows(IllegalStateException.class, () -> BlockingDialogs.runLater(() -> {}));
        }

        @Test
        public void runLaterFromBackgroundThreadThrows() throws Exception {
            final UI ui = UI.getCurrent();
            final AtomicReference<Throwable> thrown = new AtomicReference<>();
            final Thread thread = new Thread(() -> {
                UI.setCurrent(ui);
                try {
                    BlockingDialogs.runLater(() -> {});
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
            assertThrows(IllegalStateException.class,
                    () -> BlockingDialogs.parkAndAwait(UI.getCurrent(), new CompletableFuture<>()));
            assertThrows(IllegalStateException.class, BlockingDialogs::checkInUIFiber);
            final ConfirmDialog dialog = confirmDialog();
            assertThrows(IllegalStateException.class, () -> BlockingDialogs.showAndAwait(dialog));
            assertFalse(dialog.isOpened());
        }

        @Test
        public void uiFiberStartsAfterTheListenerReturns() {
            BlockingDialogs.runLater(() -> log.add("UI fiber"));
            log.add("listener");
            ScriptedBlockingExecutor.endRequest();
            assertEquals(List.of("listener", "UI fiber"), log);
        }

        @Test
        public void runUntilParkReturnsOnceTheUIFiberParks() {
            ScriptedBlockingExecutor.user.add(() -> _fireConfirm(_get(ConfirmDialog.class)));
            BlockingDialogs.runUntilPark(() -> log.add("answer: " + BlockingDialogs.showAndAwait(confirmDialog())));
            log.add("listener");
            assertEquals(List.of("answer: CONFIRM", "listener"), log);
        }

        @Test
        public void runLaterInsideUIFiberStartsAtItsPark() {
            ScriptedBlockingExecutor.user.add(() -> log.add("user answers"));
            BlockingDialogs.runLater(() -> {
                log.add("A starts");
                BlockingDialogs.runLater(() -> log.add("B runs"));
                log.add("A parks");
                BlockingDialogs.parkAndAwait(UI.getCurrent(), CompletableFuture.completedFuture("x"));
                log.add("A resumes");
            });
            ScriptedBlockingExecutor.endRequest();
            assertEquals(List.of("A starts", "A parks", "B runs", "user answers", "A resumes"), log);
        }

        @Test
        public void accessSynchronouslyInsideUIFiberRunsInline() {
            ScriptedBlockingExecutor.user.add(() -> _fireReject(_get(ConfirmDialog.class)));
            BlockingDialogs.runLater(() -> {
                final ConfirmDialogOutcome outcome = BlockingDialogs.accessSynchronously(UI.getCurrent(),
                        () -> BlockingDialogs.showAndAwait(confirmDialog()));
                log.add("nested: " + outcome);
            });
            ScriptedBlockingExecutor.endRequest();
            assertEquals(List.of("nested: REJECT"), log);
        }

        @Test
        public void accessSynchronouslyOutsideUIFiberWithLockThrows() {
            assertThrows(IllegalStateException.class,
                    () -> BlockingDialogs.accessSynchronously(UI.getCurrent(), () -> "x"));
        }

        @Test
        public void accessSynchronouslyFromBackgroundThreadWaitsForTheUIFiberParksIncluded() throws Exception {
            final UI ui = UI.getCurrent();
            ScriptedBlockingExecutor.user.add(() -> _fireConfirm(_get(ConfirmDialog.class)));
            final AtomicReference<Object> result = new AtomicReference<>();
            final Thread job = new Thread(() -> result.set(BlockingDialogs.accessSynchronously(ui,
                    () -> "the user said " + BlockingDialogs.showAndAwait(confirmDialog()))));
            job.start();
            awaitPendingAccess();
            MockVaadin.runUIQueue();
            job.join(5000);
            assertEquals("the user said CONFIRM", result.get());
        }

        @Test
        public void accessSynchronouslyFromBackgroundThreadRethrowsToTheCaller() throws Exception {
            final UI ui = UI.getCurrent();
            final List<Throwable> errors = collectErrors();
            final IllegalArgumentException boom = new IllegalArgumentException("boom");
            final AtomicReference<Throwable> thrown = new AtomicReference<>();
            final Thread job = new Thread(() -> {
                try {
                    BlockingDialogs.accessSynchronously(ui, () -> {
                        throw boom;
                    });
                } catch (Throwable t) {
                    thrown.set(t);
                }
            });
            job.start();
            awaitPendingAccess();
            MockVaadin.runUIQueue(true);
            job.join(5000);
            assertSame(boom, thrown.get());
            assertEquals(List.of(), errors);
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

    /**
     * Collects what reaches the session's error handler; pair with {@code MockVaadin.runUIQueue(true)}.
     */
    private static List<Throwable> collectErrors() {
        final List<Throwable> errors = new ArrayList<>();
        VaadinSession.getCurrent().setErrorHandler(new ErrorHandlerSpy(errors, new AtomicReference<>()));
        return errors;
    }

    private record ErrorHandlerSpy(List<Throwable> errors, AtomicReference<UI> uiInHandler)
            implements com.vaadin.flow.server.ErrorHandler {
        @Override
        public void error(com.vaadin.flow.server.ErrorEvent event) {
            errors.add(event.getThrowable());
            uiInHandler.set(UI.getCurrent());
        }
    }

    private static ConfirmDialog confirmDialog() {
        final ConfirmDialog dialog = new ConfirmDialog();
        dialog.setText("Sure?");
        dialog.setRejectable(true);
        dialog.setCancelable(true);
        return dialog;
    }

    private static void serialize(Object o) throws IOException {
        try (ObjectOutputStream out = new ObjectOutputStream(new ByteArrayOutputStream())) {
            out.writeObject(o);
        }
    }
}
