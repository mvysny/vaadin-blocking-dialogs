/*
 * Copyright 2026 Martin Vysny
 *
 * Licensed under the MIT License. See the LICENSE file in the project root
 * for the full license text.
 */
package com.example.blockingdialogs;

import com.github.mvysny.blockingdialogs.BlockingDialogs;
import com.vaadin.flow.component.ClickEvent;
import com.vaadin.flow.component.ComponentEventListener;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.progressbar.ProgressBar;
import com.vaadin.flow.shared.Registration;
import org.jspecify.annotations.Nullable;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.DoubleConsumer;
import java.util.function.Function;

/**
 * A modal progress bar with a Cancel button. {@link #runInBackground} is the blocking form, a job
 * behind the dialog:
 * <pre>{@code
 * UIFibers.runLater(() -> {
 *     Integer imported = ProgressDialog.runInBackground("Importing", progress -> importAll(progress));
 *     Notification.show(imported == null ? "Cancelled" : "Imported " + imported);
 * });
 * }</pre>
 */
public class ProgressDialog extends Dialog {
    private static final ExecutorService jobs = Executors.newVirtualThreadPerTaskExecutor();

    private final ProgressBar bar = new ProgressBar();
    private final Button cancel = new Button("Cancel");

    public ProgressDialog(String title) {
        setHeaderTitle(title);
        bar.setWidth("20em");
        add(bar);
        getFooter().add(cancel);
        addDialogCloseActionListener(e -> cancel.click());
    }

    /**
     * @param progress from 0 to 1.
     */
    public void setProgress(double progress) {
        bar.setValue(progress);
    }

    /**
     * Fires on Cancel and on Escape.
     */
    public Registration addCancelListener(ComponentEventListener<ClickEvent<Button>> listener) {
        return cancel.addClickListener(listener);
    }

    // demo:begin runInBackground
    /**
     * Runs {@code job} on a background thread behind a progress dialog, and parks the calling UI
     * fiber until the job ends or the user cancels it - the session lock free meanwhile, so the
     * progress reaches the browser.
     *
     * @param job gets a callback taking progress from 0 to 1, safe to call from the job's thread;
     *            is interrupted on Cancel.
     * @return what {@code job} returned, or {@code null} if the user cancelled it.
     * @throws IllegalStateException outside a UI fiber.
     * @apiNote Cancel is an answer, so it completes the awaited outcome rather than cancel it, which
     * would fail the UI fiber (see {@code UIFibers}' class doc). A wait that dies - the tab closed -
     * cancels the outcome instead, and that interrupts the job too.
     */
    public static <T> @Nullable T runInBackground(String title, Function<DoubleConsumer, T> job) {
        final UI ui = UI.getCurrent();
        final ProgressDialog dialog = new ProgressDialog(title);
        final CompletableFuture<@Nullable T> outcome = new CompletableFuture<>();
        final Future<?> task = jobs.submit(() -> {
            try {
                outcome.complete(job.apply(progress -> ui.access(() -> dialog.setProgress(progress))));
            } catch (Throwable t) {
                outcome.completeExceptionally(t);
            }
        });
        // the answer first: the interrupted job would otherwise fail the outcome before Cancel completes it
        dialog.addCancelListener(e -> outcome.complete(null));
        outcome.whenComplete((result, failure) -> task.cancel(true));
        return BlockingDialogs.showAndAwait(dialog, outcome);
    }
    // demo:end runInBackground
}
