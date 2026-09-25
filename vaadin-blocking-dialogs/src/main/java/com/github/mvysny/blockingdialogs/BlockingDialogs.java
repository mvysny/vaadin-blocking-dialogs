/*
 * Copyright 2026 Martin Vysny
 *
 * Licensed under the MIT License. See the LICENSE file in the project root
 * for the full license text.
 */
package com.github.mvysny.blockingdialogs;

import com.github.mvysny.blockingdialogs.uifiber.spi.Completable;
import com.vaadin.flow.component.ComponentEvent;
import com.vaadin.flow.component.ComponentEventListener;
import com.vaadin.flow.component.confirmdialog.ConfirmDialog;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.shared.Registration;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;

/**
 * Blocking dialogs, the way Swing's {@code JOptionPane} does them: inside a UI fiber, started with
 * {@link UIFibers#runLater}, a dialog call returns the user's answer.
 * <pre>{@code
 * button.addClickListener(e -> UIFibers.runLater(() -> {
 *     ConfirmDialog dialog = new ConfirmDialog();
 *     dialog.setText("Delete " + file + "?");
 *     dialog.setCancelable(true);
 *     if (BlockingDialogs.showAndAwait(dialog) == ConfirmDialogOutcome.CONFIRM) {
 *         delete(file);
 *     }
 * }));
 * }</pre>
 * Any other wait is {@link UIFibers#parkAndAwait}, anchored to the component whose life it belongs to.
 */
public final class BlockingDialogs {
    private BlockingDialogs() {
    }

    /**
     * Opens {@code dialog}, parks until {@code answer} completes, and closes the dialog again, however
     * the wait ended. The app builds the dialog; its buttons complete {@code answer}.
     * <pre>{@code
     * CompletableFuture<String> name = new CompletableFuture<>();
     * TextField field = new TextField("File name");
     * Dialog dialog = new Dialog(field);
     * dialog.getFooter().add(new Button("Save", e -> name.complete(field.getValue())));
     * dialog.addDialogCloseActionListener(e -> name.complete(null));   // Escape answers too
     * String fileName = BlockingDialogs.showAndAwait(dialog, name);
     * }</pre>
     *
     * @apiNote Without a {@link Dialog.DialogCloseActionEvent} listener, Escape and an outside click
     * close the dialog without answering; the closed dialog detaches, which ends the wait and the
     * UI fiber.
     * @param dialog the anchor of the wait.
     * @return the value {@code answer} completed with.
     * @throws IllegalStateException outside a UI fiber.
     * @throws CancellationException if the dialog detached without an answer.
     */
    public static <T extends @Nullable Object> T showAndAwait(Dialog dialog, CompletableFuture<T> answer) {
        Objects.requireNonNull(answer);
        UIFibers.checkInUIFiber();
        dialog.open();
        try {
            return UIFibers.parkAndAwait(dialog, answer);
        } finally {
            dialog.close();
        }
    }

    /**
     * Opens {@code dialog}, parks until the user answers, and closes the dialog again, however the
     * wait ended. The app configures the dialog fully - texts, which buttons, themes.
     * <pre>{@code
     * ConfirmDialog dialog = new ConfirmDialog();
     * dialog.setText("Save changes before closing?");
     * dialog.setRejectable(true);
     * dialog.setCancelable(true);
     * switch (BlockingDialogs.showAndAwait(dialog)) {
     *     case CONFIRM -> save();
     *     case REJECT -> discard();
     *     case CANCEL -> { return; }
     * }
     * }</pre>
     * The dialog may be shown again: the listeners added here are removed on return.
     *
     * @throws IllegalStateException outside a UI fiber.
     * @throws CancellationException if the dialog detached without an answer.
     */
    public static ConfirmDialogOutcome showAndAwait(ConfirmDialog dialog) {
        final Completable<ConfirmDialogOutcome> answer = UIFibers.newCompletable();
        final List<Registration> listeners = List.of(
                dialog.addConfirmListener(new Answer<>(answer, ConfirmDialogOutcome.CONFIRM)),
                dialog.addRejectListener(new Answer<>(answer, ConfirmDialogOutcome.REJECT)),
                dialog.addCancelListener(new Answer<>(answer, ConfirmDialogOutcome.CANCEL)),
                // Escape with the Cancel button hidden closes the dialog but sends no CancelEvent
                dialog.addClosedListener(new Answer<>(answer, ConfirmDialogOutcome.CANCEL)));
        dialog.open();
        try {
            return UIFibers.awaitAnchored(dialog, answer, null);
        } finally {
            dialog.close();
            listeners.forEach(Registration::remove);
        }
    }

    /**
     * Wakes the UI fiber with a fixed outcome, the first event winning. A dialog event holds the
     * session lock, so it wakes the UI fiber directly.
     *
     * @implNote A class with a transient wake-up rather than a lambda, which would drag the
     * non-serializable wake-up into the session's serialized form while the dialog is open.
     */
    private static final class Answer<E extends ComponentEvent<?>> implements ComponentEventListener<E> {
        @Nullable
        private final transient Completable<ConfirmDialogOutcome> answer;
        private final ConfirmDialogOutcome outcome;

        Answer(Completable<ConfirmDialogOutcome> answer, ConfirmDialogOutcome outcome) {
            this.answer = answer;
            this.outcome = outcome;
        }

        @Override
        public void onComponentEvent(E event) {
            if (answer != null) {
                answer.complete(outcome);
            }
        }
    }
}
