/*
 * Copyright 2026 Martin Vysny
 *
 * Licensed under the MIT License. See the LICENSE file in the project root
 * for the full license text.
 */
package com.example.blockingdialogs;

import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.textfield.TextArea;
import com.vaadin.flow.component.textfield.TextField;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static com.example.blockingdialogs.DemoTesting.clickButton;
import static com.example.blockingdialogs.DemoTesting.confirmDialog;
import static com.github.mvysny.kaributesting.v10.LocatorJ.*;
import static com.github.mvysny.kaributesting.v10.NotificationsKt.expectNoNotifications;
import static com.github.mvysny.kaributesting.v10.NotificationsKt.expectNotifications;
import static com.github.mvysny.kaributesting.v10.pro.ConfirmDialogKt._fireCancel;
import static com.github.mvysny.kaributesting.v10.pro.ConfirmDialogKt._fireConfirm;
import static com.github.mvysny.kaributesting.v10.pro.ConfirmDialogKt._fireReject;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link SaveChangesView}, the same tests for its blocking and its callback button.
 */
public abstract class SaveChangesTests {
    private final String closeButton;

    protected SaveChangesTests(String closeButton) {
        this.closeButton = closeButton;
    }

    @BeforeEach
    public void navigate() {
        UI.getCurrent().navigate(SaveChangesView.class);
    }

    @AfterEach
    public void noDialogLeftOpen() {
        _assertNoDialogs();
    }

    @Test
    public void anEmptyDocumentClosesWithoutAsking() {
        _setValue(_get(TextArea.class), "");
        clickButton(closeButton);
        expectNotifications("Closed");
    }

    @Test
    public void noClosesWithoutSaving() {
        clickButton(closeButton);
        _fireReject(confirmDialog("Save changes before closing?"));
        expectNotifications("Closed without saving");
        assertEquals("", _get(TextArea.class).getValue());
        assertEquals(FileStore.SEEDED, FileStore.current().names());
    }

    @Test
    public void cancelKeepsTheDocumentOpen() {
        clickButton(closeButton);
        _fireCancel(confirmDialog("Save changes before closing?"));
        expectNoNotifications();
        assertEquals(SaveChangesView.DRAFT, _get(TextArea.class).getValue());
    }

    @Test
    public void yesSavesUnderANewName() {
        clickButton(closeButton);
        _fireConfirm(confirmDialog("Save changes before closing?"));
        answerFileName("letter.txt");
        expectNotifications("Saved letter.txt and closed");
        assertEquals(SaveChangesView.DRAFT.length(), length("letter.txt"));
    }

    @Test
    public void anExistingNameAsksToOverwrite() {
        clickButton(closeButton);
        _fireConfirm(confirmDialog("Save changes before closing?"));
        answerFileName("notes.txt");
        _fireConfirm(confirmDialog("notes.txt exists. Overwrite?"));
        expectNotifications("Saved notes.txt and closed");
        assertEquals(SaveChangesView.DRAFT.length(), length("notes.txt"));
    }

    @Test
    public void refusingToOverwriteAsksForTheNameAgain() {
        clickButton(closeButton);
        _fireConfirm(confirmDialog("Save changes before closing?"));
        answerFileName("notes.txt");
        _fireCancel(confirmDialog("notes.txt exists. Overwrite?"));
        answerFileName("letter.txt");
        expectNotifications("Saved letter.txt and closed");
        assertEquals("The contents of notes.txt".length(), length("notes.txt"));
    }

    @Test
    public void cancellingTheFileNameKeepsTheDocumentOpen() {
        clickButton(closeButton);
        _fireConfirm(confirmDialog("Save changes before closing?"));
        clickButton(_get(PromptDialog.class), "Cancel");
        expectNoNotifications();
        assertEquals(SaveChangesView.DRAFT, _get(TextArea.class).getValue());
        assertEquals(FileStore.SEEDED, FileStore.current().names());
    }

    private static void answerFileName(String name) {
        final PromptDialog prompt = _get(PromptDialog.class);
        _setValue(_get(prompt, TextField.class, spec -> spec.withLabel("File name")), name);
        _click(_get(prompt, Button.class, spec -> spec.withText("OK")));
    }

    private static int length(String name) {
        return FileStore.current().list().stream().filter(file -> file.name().equals(name)).findFirst()
                .orElseThrow(() -> new AssertionError(name + " was not saved")).length();
    }
}
