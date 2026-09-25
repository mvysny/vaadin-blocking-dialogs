/*
 * Copyright 2026 Martin Vysny
 *
 * Licensed under the MIT License. See the LICENSE file in the project root
 * for the full license text.
 */
package com.example.blockingdialogs;

import com.github.mvysny.blockingdialogs.BlockingDialogs;
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

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * A loop with a dialog per item - "Delete <i>x</i>?" with Yes / No / Yes to all / Cancel - where
 * callback style hurts the most: the loop has to become recursion.
 */
@Route(value = "delete-files", layout = MainLayout.class)
@PageTitle("Delete files")
public class DeleteFilesView extends VerticalLayout {
    private final FileList files = new FileList();
    private final FileStore store = FileStore.current();

    public DeleteFilesView() {
        add(new H2("Delete files"),
                new Paragraph("Delete every file, asking about each: a loop over dialogs. The callbacks"
                        + " button does the same with listeners."));
        final Button deleteAll = new Button("Delete all");
        deleteAll.addThemeVariants(ButtonVariant.PRIMARY);
        // demo:begin blocking
        deleteAll.addClickListener(e -> UIFibers.runLater(this::deleteAll));
        // demo:end blocking
        add(new HorizontalLayout(deleteAll, new Button("Delete all (callbacks)", e -> deleteAllWithCallbacks()),
                new Button("Restore files", e -> {
                    store.restore();
                    files.refresh();
                })), files);
        add(new TryThis("Delete all: Yes, No, then Yes to all - one file is left.",
                "Escape answers Cancel: the files deleted so far stay deleted."));
        final HorizontalLayout sources = new HorizontalLayout(
                new SourceCode("Blocking", DeleteFilesView.class, "blocking"),
                new SourceCode("Callbacks", DeleteFilesView.class, "callbacks"));
        sources.setWrap(true);
        sources.setWidthFull();
        add(sources);
    }

    // demo:begin blocking
    private void deleteAll() {
        boolean yesToAll = false;
        int deleted = 0;
        for (String name : store.names()) {
            if (!yesToAll) {
                switch (askDelete(name)) {
                    case YES -> {
                    }
                    case NO -> {
                        continue;
                    }
                    case YES_TO_ALL -> yesToAll = true;
                    case CANCEL -> {
                        finish(deleted);
                        return;
                    }
                }
            }
            delete(name);
            deleted++;
        }
        finish(deleted);
    }

    /**
     * A dialog with four buttons: the app's own, awaited on the future its buttons complete.
     */
    private static DeleteDialog.Answer askDelete(String name) {
        final DeleteDialog dialog = new DeleteDialog(name);
        final CompletableFuture<DeleteDialog.Answer> answer = new CompletableFuture<>();
        dialog.addAnswerListener(e -> answer.complete(e.getAnswer()));
        return BlockingDialogs.showAndAwait(dialog, answer);
    }
    // demo:end blocking

    // demo:begin callbacks
    private void deleteAllWithCallbacks() {
        deleteFrom(store.names(), 0, 0);
    }

    /**
     * The loop's body, called again from the listener for the next file.
     */
    private void deleteFrom(List<String> names, int index, int deleted) {
        if (index == names.size()) {
            finish(deleted);
            return;
        }
        final String name = names.get(index);
        final DeleteDialog dialog = new DeleteDialog(name);
        dialog.addAnswerListener(e -> {
            dialog.close();
            switch (e.getAnswer()) {
                case YES -> {
                    delete(name);
                    deleteFrom(names, index + 1, deleted + 1);
                }
                case NO -> deleteFrom(names, index + 1, deleted);
                case YES_TO_ALL -> {
                    final List<String> rest = names.subList(index, names.size());
                    rest.forEach(this::delete);
                    finish(deleted + rest.size());
                }
                case CANCEL -> finish(deleted);
            }
        });
        dialog.open();
    }
    // demo:end callbacks

    private void delete(String name) {
        store.delete(name);
        files.refresh();
    }

    private static void finish(int deleted) {
        Notification.show("Deleted " + deleted + (deleted == 1 ? " file" : " files"));
    }
}
