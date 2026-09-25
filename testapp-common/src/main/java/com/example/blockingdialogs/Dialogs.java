/*
 * Copyright 2026 Martin Vysny
 *
 * Licensed under the MIT License. See the LICENSE file in the project root
 * for the full license text.
 */
package com.example.blockingdialogs;

import com.github.mvysny.blockingdialogs.BlockingDialogs;
import com.github.mvysny.blockingdialogs.ConfirmDialogOutcome;
import com.vaadin.flow.component.confirmdialog.ConfirmDialog;
import org.jspecify.annotations.Nullable;

import java.util.concurrent.CompletableFuture;

/**
 * The app's own blocking dialogs, {@code JOptionPane}-style, each a few lines over
 * {@link BlockingDialogs} - the library ships none, since every app wants its own texts and buttons.
 * Call them inside a UI fiber:
 * <pre>{@code
 * button.addClickListener(e -> UIFibers.runLater(() -> {
 *     if (Dialogs.confirm("Delete notes.txt?")) {
 *         store.delete("notes.txt");
 *     }
 * }));
 * }</pre>
 * The {@code new...} builders make the same dialogs for callback-style code.
 */
public final class Dialogs {
    private Dialogs() {
    }

    /**
     * @return whether the user said Yes; No and Escape say no.
     */
    public static boolean confirm(String question) {
        return BlockingDialogs.showAndAwait(newConfirm(question)) == ConfirmDialogOutcome.CONFIRM;
    }

    /**
     * @return {@code CONFIRM} for Yes, {@code REJECT} for No, {@code CANCEL} for Cancel and Escape.
     */
    public static ConfirmDialogOutcome yesNoCancel(String question) {
        return BlockingDialogs.showAndAwait(newYesNoCancel(question));
    }

    /**
     * @return the text entered, or {@code null} on Cancel and Escape.
     */
    public static @Nullable String prompt(String label, String initialValue) {
        final PromptDialog dialog = new PromptDialog(label, initialValue);
        final CompletableFuture<@Nullable String> answer = new CompletableFuture<>();
        dialog.addAnswerListener(e -> answer.complete(e.getValue()));
        return BlockingDialogs.showAndAwait(dialog, answer);
    }

    /**
     * A Yes / No confirm; No is its cancel button, so Escape fires the cancel event too.
     */
    public static ConfirmDialog newConfirm(String question) {
        final ConfirmDialog dialog = new ConfirmDialog();
        dialog.setText(question);
        dialog.setConfirmText("Yes");
        dialog.setCancelable(true);
        dialog.setCancelText("No");
        return dialog;
    }

    public static ConfirmDialog newYesNoCancel(String question) {
        final ConfirmDialog dialog = new ConfirmDialog();
        dialog.setText(question);
        dialog.setConfirmText("Yes");
        dialog.setRejectable(true);
        dialog.setRejectText("No");
        dialog.setCancelable(true);
        return dialog;
    }
}
