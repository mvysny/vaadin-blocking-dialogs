/*
 * Copyright 2026 Martin Vysny
 *
 * Licensed under the MIT License. See the LICENSE file in the project root
 * for the full license text.
 */
package com.example.blockingdialogs;

import com.github.mvysny.kaributesting.v10.MockBrowser;
import com.github.mvysny.kaributesting.v10.MockVaadin;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.dialog.Dialog;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.example.blockingdialogs.DemoTesting.awaitUntil;
import static com.example.blockingdialogs.DemoTesting.clickButton;
import static com.example.blockingdialogs.DemoTesting.confirmDialog;
import static com.example.blockingdialogs.DemoTesting.expectFiberLog;
import static com.example.blockingdialogs.DemoTesting.takeErrors;
import static com.github.mvysny.kaributesting.v10.LocatorJ.*;
import static com.github.mvysny.kaributesting.v10.NotificationsKt.expectNotifications;
import static com.github.mvysny.kaributesting.v10.pro.ConfirmDialogKt._fireCancel;
import static com.github.mvysny.kaributesting.v10.pro.ConfirmDialogKt._fireConfirm;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link EdgeCasesView}: each button's UI fiber, checked through the {@link FiberLog} it writes.
 */
public abstract class EdgeCasesTests {
    @BeforeEach
    public void navigate() {
        UI.getCurrent().navigate(EdgeCasesView.class);
    }

    @Test
    public void escapeEndsTheUIFiberQuietly() {
        clickButton("Escape without an answer");
        escapeDialog().close();
        expectFiberLog("Escape: the dialog closed unanswered, so its wait died", "Escape: finally ran");
        _assertNoDialogs();
    }

    @Test
    public void okAnswersTheEscapeDialog() {
        clickButton("Escape without an answer");
        clickButton(escapeDialog(), "OK");
        expectFiberLog("Escape: answered OK", "Escape: finally ran");
        _assertNoDialogs();
    }

    @Test
    public void f5EndsTheUIFiberHere() {
        clickButton("F5 with a dialog open");
        confirmDialog("Press F5 now");
        UI.getCurrent().getPage().reload();
        expectFiberLog("F5 here: finally ran");
    }

    @Test
    public void closingTheTabRunsFinally() {
        final String first = MockBrowser.getCurrentWindowName();
        MockBrowser.newTab("second", "edge-cases");
        clickButton("Close the tab with a dialog open");
        confirmDialog("Close this browser tab now");

        MockBrowser.switchTo(first);
        MockBrowser.closeTab("second");

        expectFiberLog("Tab close: finally ran");
    }

    @Test
    public void anExceptionReachesTheErrorHandler() {
        clickButton("Throw after the dialog");
        _fireConfirm(confirmDialog("Throw an exception now?"));
        MockVaadin.clientRoundtrip(true);  // to the app's ErrorHandler, as in production
        expectNotifications("Error: Thrown by a UI fiber after its dialog");
        final List<Throwable> errors = takeErrors();
        assertEquals(1, errors.size());
        assertInstanceOf(IllegalStateException.class, errors.get(0));
    }

    @Test
    public void twoUIFibersAnswerIndependently() {
        clickButton("Two UI fibers at once");
        _fireConfirm(confirmDialog("Inner UI fiber"));
        _fireCancel(confirmDialog("Outer UI fiber"));
        expectFiberLog("Two UI fibers: the inner one got Yes", "Two UI fibers: the outer one got No");
    }

    @Test
    public void aUIFiberResumesOnItsOwnThread() {
        clickButton("Which thread?");
        _fireConfirm(confirmDialog("This UI fiber runs on"));
        awaitUntil("the UI fiber resumes", () -> !FiberLog.current().entries().isEmpty());
        final String entry = FiberLog.current().entries().get(0);
        assertTrue(entry.startsWith("Which thread: parked and resumed on "), entry);
    }

    /**
     * Karibu can't drop a click the way Vaadin's server-side modality does, so this checks what
     * makes Vaadin drop it: once the click's response is written, the button is inert.
     */
    @Test
    public void aSecondClickFindsTheDialogOpen() {
        final Button save = _get(Button.class, spec -> spec.withText("Save (double-click me)"));
        _click(save);
        MockVaadin.clientRoundtrip();
        // Flow resolves inertness when it writes the response, which Karibu never does
        UI.getCurrent().getInternals().getStateTree().collectChanges(change -> {
        });
        assertTrue(save.getElement().getNode().isInert(), "the second click is dropped");
        _fireConfirm(confirmDialog("Saved once"));
        expectFiberLog("Double-click: a click started a UI fiber");
    }

    private static Dialog escapeDialog() {
        return _get(Dialog.class, spec -> spec.withPredicate(dialog -> "Escape".equals(dialog.getHeaderTitle())));
    }
}
