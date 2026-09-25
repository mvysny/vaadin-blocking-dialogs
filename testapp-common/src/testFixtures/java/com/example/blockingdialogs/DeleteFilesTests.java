/*
 * Copyright 2026 Martin Vysny
 *
 * Licensed under the Apache License, Version 2.0. See the LICENSE file in the
 * project root for the full license text.
 */
package com.example.blockingdialogs;

import com.vaadin.flow.component.UI;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.example.blockingdialogs.DemoTesting.clickButton;
import static com.github.mvysny.kaributesting.v10.GridKt._size;
import static com.github.mvysny.kaributesting.v10.LocatorJ._assertNoDialogs;
import static com.github.mvysny.kaributesting.v10.LocatorJ._get;
import static com.github.mvysny.kaributesting.v10.NotificationsKt.expectNotifications;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link DeleteFilesView}, the same tests for its blocking and its callback button.
 */
public abstract class DeleteFilesTests {
    private final String deleteButton;

    protected DeleteFilesTests(String deleteButton) {
        this.deleteButton = deleteButton;
    }

    @BeforeEach
    public void navigate() {
        UI.getCurrent().navigate(DeleteFilesView.class);
    }

    @AfterEach
    public void noDialogLeftOpen() {
        _assertNoDialogs();
    }

    @Test
    public void yesNoThenYesToAll() {
        clickButton(deleteButton);
        answer("draft.txt", "Yes");
        answer("ideas.txt", "No");
        answer("notes.txt", "Yes to all");
        expectNotifications("Deleted 4 files");
        assertEquals(List.of("ideas.txt"), FileStore.current().names());
        assertEquals(1, _size(_get(FileList.class)));
    }

    @Test
    public void cancelKeepsTheRest() {
        clickButton(deleteButton);
        answer("draft.txt", "Yes");
        answer("ideas.txt", "Cancel");
        expectNotifications("Deleted 1 file");
        assertEquals(List.of("ideas.txt", "notes.txt", "shopping.txt", "todo.txt"), FileStore.current().names());
    }

    @Test
    public void noToEveryFileDeletesNothing() {
        clickButton(deleteButton);
        FileStore.SEEDED.forEach(name -> answer(name, "No"));
        expectNotifications("Deleted 0 files");
        assertEquals(FileStore.SEEDED, FileStore.current().names());
    }

    @Test
    public void restoreBringsTheFilesBack() {
        clickButton(deleteButton);
        answer("draft.txt", "Yes to all");
        expectNotifications("Deleted 5 files");
        clickButton("Restore files");
        assertEquals(FileStore.SEEDED, FileStore.current().names());
        assertEquals(5, _size(_get(FileList.class)));
    }

    private static void answer(String file, String button) {
        final DeleteDialog dialog = _get(DeleteDialog.class);
        assertEquals("Delete " + file + "?", dialog.getHeaderTitle());
        clickButton(dialog, button);
    }
}
