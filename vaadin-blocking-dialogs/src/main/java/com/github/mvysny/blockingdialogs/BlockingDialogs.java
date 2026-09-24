/*
 * Copyright 2026 Martin Vysny
 *
 * Licensed under the MIT License. See the LICENSE file in the project root
 * for the full license text.
 */
package com.github.mvysny.blockingdialogs;

import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.ComponentEvent;
import com.vaadin.flow.component.ComponentEventListener;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.confirmdialog.ConfirmDialog;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.shared.Registration;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/**
 * Blocking dialogs, the way Swing's {@code JOptionPane} does them: start a block with
 * {@link #runLater}, and inside it a dialog call returns the user's answer.
 * <pre>{@code
 * button.addClickListener(e -> BlockingDialogs.runLater(() -> {
 *     ConfirmDialog dialog = new ConfirmDialog();
 *     dialog.setText("Delete " + file + "?");
 *     dialog.setCancelable(true);
 *     if (BlockingDialogs.showAndAwait(dialog) == ConfirmDialogOutcome.CONFIRM) {
 *         delete(file);
 *     }
 * }));
 * }</pre>
 * Any other wait is {@link #parkAndAwait}, anchored to the component whose life it belongs to: a Save
 * button's progress bar, a background job's dialog.
 * <p>
 * Every method is its namesake on {@link BlockingExecutor#get()}, the strategy on the classpath.
 * <p>
 * <b>Cancellation means "the wait is dead", nothing else.</b> A user-facing Cancel is an answer: the
 * dialog completes its future with a cancel value. So a progress dialog's Cancel must not cancel
 * the future the block awaits, or the block ends silently; await an outcome future, and let Cancel
 * stop the job and complete the outcome.
 */
public final class BlockingDialogs {
    private BlockingDialogs() {
    }

    /**
     * {@link BlockingExecutor#runLater}.
     */
    public static void runLater(@NotNull Runnable block) {
        BlockingExecutor.get().runLater(block);
    }

    /**
     * {@link BlockingExecutor#runUntilPark}.
     */
    public static void runUntilPark(@NotNull Runnable block) {
        BlockingExecutor.get().runUntilPark(block);
    }

    /**
     * {@link BlockingExecutor#access}, for background threads.
     */
    public static void access(@NotNull UI ui, @NotNull Runnable block) {
        BlockingExecutor.get().access(ui, block);
    }

    /**
     * {@link BlockingExecutor#accessSynchronously(UI, Runnable)}.
     */
    public static void accessSynchronously(@NotNull UI ui, @NotNull Runnable block) {
        BlockingExecutor.get().accessSynchronously(ui, block);
    }

    /**
     * {@link BlockingExecutor#accessSynchronously(UI, Supplier)}.
     */
    public static <T> T accessSynchronously(@NotNull UI ui, @NotNull Supplier<T> block) {
        return BlockingExecutor.get().accessSynchronously(ui, block);
    }

    /**
     * {@link BlockingExecutor#parkAndAwait}.
     *
     * @throws IllegalStateException outside a block.
     */
    public static <T> T parkAndAwait(@NotNull Component anchor, @NotNull CompletableFuture<T> future) {
        return BlockingExecutor.get().parkAndAwait(anchor, future);
    }

    /**
     * {@link BlockingExecutor#checkUIThreadWithBlockingCapabilities()}.
     *
     * @throws IllegalStateException outside a block.
     */
    public static void checkUIThreadWithBlockingCapabilities() {
        BlockingExecutor.get().checkUIThreadWithBlockingCapabilities();
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
     * block.
     * @param dialog the anchor of the wait.
     * @return the value {@code answer} completed with.
     * @throws IllegalStateException outside a block.
     * @throws CancellationException if the dialog detached without an answer.
     */
    public static <T> T showAndAwait(@NotNull Dialog dialog, @NotNull CompletableFuture<T> answer) {
        Objects.requireNonNull(answer);
        final BlockingExecutor executor = BlockingExecutor.get();
        executor.checkUIThreadWithBlockingCapabilities();
        dialog.open();
        try {
            return executor.parkAndAwait(dialog, answer);
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
     * @throws IllegalStateException outside a block.
     * @throws CancellationException if the dialog detached without an answer.
     */
    @NotNull
    public static ConfirmDialogOutcome showAndAwait(@NotNull ConfirmDialog dialog) {
        final BlockingExecutor executor = BlockingExecutor.get();
        executor.checkUIThreadWithBlockingCapabilities();
        final CompletableFuture<ConfirmDialogOutcome> answer = new CompletableFuture<>();
        final List<Registration> listeners = List.of(
                dialog.addConfirmListener(new Answer<>(answer, ConfirmDialogOutcome.CONFIRM)),
                dialog.addRejectListener(new Answer<>(answer, ConfirmDialogOutcome.REJECT)),
                dialog.addCancelListener(new Answer<>(answer, ConfirmDialogOutcome.CANCEL)),
                // Escape with the Cancel button hidden closes the dialog but sends no CancelEvent
                dialog.addClosedListener(new Answer<>(answer, ConfirmDialogOutcome.CANCEL)));
        dialog.open();
        try {
            return executor.parkAndAwait(dialog, answer);
        } finally {
            dialog.close();
            listeners.forEach(Registration::remove);
        }
    }

    /**
     * Completes the answer with a fixed outcome, the first event winning.
     *
     * @implNote A class with a transient future rather than a lambda, which would drag the
     * non-serializable future into the session's serialized form while the dialog is open.
     */
    private static final class Answer<E extends ComponentEvent<?>> implements ComponentEventListener<E> {
        @Nullable
        private final transient CompletableFuture<ConfirmDialogOutcome> answer;
        @NotNull
        private final ConfirmDialogOutcome outcome;

        Answer(@NotNull CompletableFuture<ConfirmDialogOutcome> answer, @NotNull ConfirmDialogOutcome outcome) {
            this.answer = answer;
            this.outcome = outcome;
        }

        @Override
        public void onComponentEvent(@NotNull E event) {
            if (answer != null) {
                answer.complete(outcome);
            }
        }
    }
}
