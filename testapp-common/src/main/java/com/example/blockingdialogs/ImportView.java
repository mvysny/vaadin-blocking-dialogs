/*
 * Copyright 2026 Martin Vysny
 *
 * Licensed under the MIT License. See the LICENSE file in the project root
 * for the full license text.
 */
package com.example.blockingdialogs;

import com.github.mvysny.blockingdialogs.UIFibers;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.html.H2;
import com.vaadin.flow.component.html.Paragraph;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;

import java.util.concurrent.CancellationException;
import java.util.function.DoubleConsumer;

/**
 * Awaiting something that is not a dialog: a background job behind a progress dialog. Beside it
 * the wrong way, the job run on the UI fiber itself, which holds the session lock throughout and
 * so freezes the session, as a long Swing listener freezes the EDT.
 */
@Route(value = "import", layout = MainLayout.class)
@PageTitle("Import")
public class ImportView extends VerticalLayout {
    static final int RECORDS = 20;
    private static final long MILLIS_PER_RECORD = 150;

    public ImportView() {
        add(new H2("Import"),
                new Paragraph("The UI fiber waits for a background job as it waits for a dialog: the"
                        + " progress dialog shows the job's progress, and its Cancel stops the job."));
        final Button importButton = new Button("Import");
        importButton.addThemeVariants(ButtonVariant.PRIMARY);
        // demo:begin blocking
        importButton.addClickListener(e -> UIFibers.runLater(this::importRecords));
        // demo:end blocking
        // demo:begin wrong
        final Button wrongWay = new Button("Import on the UI fiber (freezes the app)",
                e -> UIFibers.runLater(this::importOnTheUIFiber));
        // demo:end wrong
        add(new HorizontalLayout(importButton, wrongWay));
        add(new TryThis("Import, and watch the clock in the header: it keeps ticking, and the bar moves.",
                "Import on the UI fiber: the clock stops until the import is done, and so does every"
                        + " other tab of this session.",
                "Cancel an import half-way: the job is interrupted."));
        final HorizontalLayout sources = new HorizontalLayout(
                new SourceCode("Blocking, on a background job", ImportView.class, "blocking"),
                new SourceCode("The wrong way", ImportView.class, "wrong"),
                new SourceCode("The progress dialog", ProgressDialog.class, "runInBackground"));
        sources.setWrap(true);
        sources.setWidthFull();
        add(sources);
    }

    // demo:begin blocking
    private void importRecords() {
        final Integer imported = ProgressDialog.runInBackground("Importing records", ImportView::importJob);
        Notification.show(imported == null ? "Import cancelled" : "Imported " + imported + " records");
    }
    // demo:end blocking

    // demo:begin wrong
    private void importOnTheUIFiber() {
        final int imported = importJob(progress -> { });
        Notification.show("Imported " + imported + " records, the app frozen meanwhile");
    }
    // demo:end wrong

    /**
     * Imports {@link #RECORDS} records, slowly.
     *
     * @throws CancellationException once interrupted.
     */
    private static int importJob(DoubleConsumer progress) {
        for (int i = 1; i <= RECORDS; i++) {
            try {
                Thread.sleep(MILLIS_PER_RECORD);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new CancellationException("The import was interrupted");
            }
            progress.accept((double) i / RECORDS);
        }
        return RECORDS;
    }
}
