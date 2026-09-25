/*
 * Copyright 2026 Martin Vysny
 *
 * Licensed under the Apache License, Version 2.0. See the LICENSE file in the
 * project root for the full license text.
 */
package com.example.blockingdialogs;

import com.github.mvysny.blockingdialogs.UIFibers;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.confirmdialog.ConfirmDialog;
import com.vaadin.flow.component.html.H2;
import com.vaadin.flow.component.html.Paragraph;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.textfield.TextArea;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;
import org.jspecify.annotations.Nullable;

/**
 * The canonical Swing flow, "Save changes before closing?": Yes / No / Cancel, on Yes a file name,
 * then "File exists, overwrite?" - once as blocking code, once with callbacks.
 */
@Route(value = "", layout = MainLayout.class)
@PageTitle("Save changes?")
public class SaveChangesView extends VerticalLayout {
    static final String DRAFT = "Dear diary, today I blocked on a dialog, and the browser didn't mind.";

    private final TextArea editor = new TextArea("Untitled document");
    private final FileList files = new FileList();
    private final FileStore store = FileStore.current();

    public SaveChangesView() {
        add(new H2("Save changes before closing?"),
                new Paragraph("Close the document: three dialogs, a file name and a loop, written as"
                        + " straight-line code. The callbacks button does the same with listeners."));
        editor.setValue(DRAFT);
        editor.setWidth("40em");
        editor.setMaxWidth("100%");
        final Button close = new Button("Close");
        close.addThemeVariants(ButtonVariant.PRIMARY);
        // demo:begin blocking
        close.addClickListener(e -> UIFibers.runLater(this::close));
        // demo:end blocking
        add(editor, new HorizontalLayout(close, new Button("Close (callbacks)", e -> closeWithCallbacks()),
                new Button("New draft", e -> editor.setValue(DRAFT))), files);
        add(new TryThis("Close, Yes, keep notes.txt: it exists, so you're asked to overwrite; say No and"
                        + " you're asked for a name again.",
                "Watch the clock in the header: it keeps ticking while a dialog is open - the session lock is free.",
                "Open this page in a second tab while a dialog is open: that tab works normally.",
                "The loading indicator at the top stops as soon as a dialog shows."));
        final HorizontalLayout sources = new HorizontalLayout(
                new SourceCode("Blocking", SaveChangesView.class, "blocking"),
                new SourceCode("Callbacks", SaveChangesView.class, "callbacks"));
        sources.setWrap(true);
        sources.setWidthFull();
        add(sources);
    }

    // demo:begin blocking
    private void close() {
        if (editor.isEmpty()) {
            closeDocument("Closed");
            return;
        }
        switch (Dialogs.yesNoCancel("Save changes before closing?")) {
            case CONFIRM -> {
                final String name = askFileName();
                if (name != null) {
                    store.save(name, editor.getValue());
                    closeDocument("Saved " + name + " and closed");
                }
            }
            case REJECT -> closeDocument("Closed without saving");
            case CANCEL -> {
            }
        }
    }

    /**
     * @return the file name to save under, or {@code null} if the user cancelled.
     */
    private @Nullable String askFileName() {
        while (true) {
            final String name = Dialogs.prompt("File name", "notes.txt");
            if (name == null || !store.exists(name) || Dialogs.confirm(name + " exists. Overwrite?")) {
                return name;
            }
        }
    }
    // demo:end blocking

    // demo:begin callbacks
    private void closeWithCallbacks() {
        if (editor.isEmpty()) {
            closeDocument("Closed");
            return;
        }
        final ConfirmDialog dialog = Dialogs.newYesNoCancel("Save changes before closing?");
        dialog.addConfirmListener(e -> askFileNameThenSave());
        dialog.addRejectListener(e -> closeDocument("Closed without saving"));
        dialog.open();
    }

    private void askFileNameThenSave() {
        final PromptDialog prompt = new PromptDialog("File name", "notes.txt");
        prompt.addAnswerListener(e -> {
            prompt.close();
            final String name = e.getValue();
            if (name == null) {
                return;
            }
            if (!store.exists(name)) {
                saveAndClose(name);
                return;
            }
            final ConfirmDialog overwrite = Dialogs.newConfirm(name + " exists. Overwrite?");
            overwrite.addConfirmListener(e2 -> saveAndClose(name));
            overwrite.addCancelListener(e2 -> askFileNameThenSave());
            overwrite.open();
        });
        prompt.open();
    }

    private void saveAndClose(String name) {
        store.save(name, editor.getValue());
        closeDocument("Saved " + name + " and closed");
    }
    // demo:end callbacks

    private void closeDocument(String message) {
        editor.clear();
        files.refresh();
        Notification.show(message);
    }
}
