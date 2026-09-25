/*
 * Copyright 2026 Martin Vysny
 *
 * Licensed under the MIT License. See the LICENSE file in the project root
 * for the full license text.
 */
package com.example.blockingdialogs;

import com.github.mvysny.blockingdialogs.BlockingDialogs;
import com.github.mvysny.blockingdialogs.ConfirmDialogOutcome;
import com.github.mvysny.blockingdialogs.UIFibers;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.confirmdialog.ConfirmDialog;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.router.Route;

/**
 * Two nested confirms, written as straight-line code.
 */
@Route("")
public class MainView extends VerticalLayout {
    public MainView() {
        add(new Button("Blocking dialog", e -> UIFibers.runLater(() -> {
            if (confirm("Are you sure?")) {
                Notification.show(confirm("Are you really sure?") ? "Yes you're sure" : "Yes but no");
            } else {
                Notification.show("Nope, not sure");
            }
        })));
    }

    /**
     * @return whether the user pressed Confirm; Cancel and Escape say no.
     */
    private static boolean confirm(String text) {
        final ConfirmDialog dialog = new ConfirmDialog();
        dialog.setText(text);
        dialog.setCancelable(true);
        return BlockingDialogs.showAndAwait(dialog) == ConfirmDialogOutcome.CONFIRM;
    }
}
