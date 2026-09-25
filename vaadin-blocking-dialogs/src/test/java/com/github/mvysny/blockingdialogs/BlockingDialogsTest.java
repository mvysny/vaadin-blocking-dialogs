/*
 * Copyright 2026 Martin Vysny
 *
 * Licensed under the Apache License, Version 2.0. See the LICENSE file in the
 * project root for the full license text.
 */
package com.github.mvysny.blockingdialogs;

import com.github.mvysny.blockingdialogs.uifiber.loom.MockVirtualThreadAwareServlet;
import com.github.mvysny.kaributesting.v10.MockVaadin;
import com.github.mvysny.kaributesting.v10.Routes;
import com.vaadin.flow.component.ComponentUtil;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.confirmdialog.ConfirmDialog;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.textfield.TextField;
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
 * The dialog helpers, on the loom runner as {@link UIFibersTest} explains.
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
        final MockVirtualThreadAwareServlet servlet = new MockVirtualThreadAwareServlet(routes);
        // the servlet's factory, not MockedUI::new: the UI keeps it, and a dialog is serialized with its UI
        MockVaadin.setup(servlet.getUiFactory(), servlet);
    }

    @AfterEach
    public void tearDownVaadin() {
        MockVaadin.tearDown();
    }

    @Nested
    public class ConfirmDialogHelper {
        @Test
        public void returnsTheAnswer() {
            for (ConfirmDialogOutcome answer : ConfirmDialogOutcome.values()) {
                UIFibers.runLater(() -> log.add("answer: " + BlockingDialogs.showAndAwait(confirmDialog("Sure?"))));
                final ConfirmDialog dialog = _get(ConfirmDialog.class);
                switch (answer) {
                    case CONFIRM -> _fireConfirm(dialog);
                    case REJECT -> _fireReject(dialog);
                    case CANCEL -> _fireCancel(dialog);
                }
                _assertNone(ConfirmDialog.class);
            }
            assertEquals(List.of("answer: CONFIRM", "answer: REJECT", "answer: CANCEL"), log);
        }

        @Test
        public void closedWithoutAnswerIsCancel() {
            UIFibers.runLater(() -> log.add("answer: " + BlockingDialogs.showAndAwait(confirmDialog("Sure?"))));
            // Escape with the Cancel button hidden: the dialog closes, no CancelEvent
            final ConfirmDialog dialog = _get(ConfirmDialog.class);
            dialog.close();
            ComponentUtil.fireEvent(dialog, new ConfirmDialog.ClosedEvent(dialog, true));
            MockVaadin.clientRoundtrip();
            assertEquals(List.of("answer: CANCEL"), log);
        }

        @Test
        public void sameDialogShownTwice() {
            final ConfirmDialog dialog = confirmDialog("Sure?");
            UIFibers.runLater(() -> {
                log.add("first: " + BlockingDialogs.showAndAwait(dialog));
                log.add("second: " + BlockingDialogs.showAndAwait(dialog));
            });
            _fireConfirm(_get(ConfirmDialog.class));
            _fireReject(_get(ConfirmDialog.class));
            MockVaadin.clientRoundtrip();
            assertEquals(List.of("first: CONFIRM", "second: REJECT"), log);
        }

        /**
         * vaadin-loom's demo: two nested confirms, one answer each.
         */
        @Test
        public void nestedDialogs() {
            UIFibers.runLater(() -> {
                if (BlockingDialogs.showAndAwait(confirmDialog("Are you sure?")) == ConfirmDialogOutcome.CONFIRM) {
                    log.add("second: " + BlockingDialogs.showAndAwait(confirmDialog("Are you really sure?")));
                }
            });
            _fireConfirm(_get(ConfirmDialog.class));
            _fireCancel(_get(ConfirmDialog.class));
            _assertNone(ConfirmDialog.class);
            assertEquals(List.of("second: CANCEL"), log);
        }

        @Test
        public void serializableWhileWaiting() {
            UIFibers.runLater(() -> log.add("answer: " + BlockingDialogs.showAndAwait(confirmDialog("Sure?"))));
            final ConfirmDialog dialog = _get(ConfirmDialog.class);
            assertDoesNotThrow(() -> serialize(dialog));
            _fireConfirm(dialog);
            MockVaadin.clientRoundtrip();
            assertEquals(List.of("answer: CONFIRM"), log);
        }

        @Test
        public void preserveOnRefreshKeepsTheWaitAndResumesOnTheNewUI() {
            UI.getCurrent().navigate(TestViews.PreservedView.class);
            final UI oldUI = UI.getCurrent();
            final AtomicReference<UI> uiAfterPark = new AtomicReference<>();
            UIFibers.runLater(() -> {
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
        }

        @Test
        public void outsideAUIFiberThrowsWithoutOpening() {
            final ConfirmDialog dialog = confirmDialog("Sure?");
            assertThrows(IllegalStateException.class, () -> BlockingDialogs.showAndAwait(dialog));
            assertFalse(dialog.isOpened());
        }
    }

    @Nested
    public class DialogHelper {
        @Test
        public void returnsTheAnswer() {
            UIFibers.runLater(() -> {
                final CompletableFuture<String> name = new CompletableFuture<>();
                final TextField field = new TextField("File name");
                final Dialog dialog = new Dialog(field, new Button("Save", e -> name.complete(field.getValue())));
                log.add("name: " + BlockingDialogs.showAndAwait(dialog, name));
            });
            _setValue(_get(TextField.class, spec -> spec.withLabel("File name")), "notes.txt");
            _click(_get(Button.class, spec -> spec.withText("Save")));
            MockVaadin.clientRoundtrip();
            assertEquals(List.of("name: notes.txt"), log);
            _assertNone(Dialog.class);
        }

        @Test
        public void closesTheDialogWhenTheWaitDies() {
            final AtomicReference<Dialog> dialog = new AtomicReference<>();
            UIFibers.runLater(() -> {
                dialog.set(new Dialog());
                try {
                    BlockingDialogs.showAndAwait(dialog.get(), new CompletableFuture<>());
                    log.add("answered");
                } finally {
                    log.add("finally");
                }
            });
            _assertOne(Dialog.class);
            dialog.get().removeFromParent();  // the dialog dies unanswered
            MockVaadin.clientRoundtrip();
            MockVaadin.clientRoundtrip();
            assertEquals(List.of("finally"), log);
            assertFalse(dialog.get().isOpened());
        }
    }

    private static ConfirmDialog confirmDialog(String text) {
        final ConfirmDialog dialog = new ConfirmDialog();
        dialog.setText(text);
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
