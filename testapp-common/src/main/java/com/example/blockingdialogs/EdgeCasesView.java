/*
 * Copyright 2026 Martin Vysny
 *
 * Licensed under the MIT License. See the LICENSE file in the project root
 * for the full license text.
 */
package com.example.blockingdialogs;

import com.github.mvysny.blockingdialogs.BlockingDialogs;
import com.github.mvysny.blockingdialogs.UIFibers;
import com.github.mvysny.blockingdialogs.WaitDiedException;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.html.H2;
import com.vaadin.flow.component.html.H3;
import com.vaadin.flow.component.html.Paragraph;
import com.vaadin.flow.component.orderedlayout.FlexLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;

import java.util.concurrent.CompletableFuture;

/**
 * The edge cases that make blocking hard, one button each; what their UI fibers did goes to the
 * {@link FiberLog}. Not {@code @PreserveOnRefresh}, unlike {@link RefreshView}: a closed tab of a
 * preserved route lingers until its heartbeat expires, and so would its parked UI fiber.
 */
@Route(value = "edge-cases", layout = MainLayout.class)
@PageTitle("Edge cases")
public class EdgeCasesView extends VerticalLayout {
    private final FiberLog log = FiberLog.current();

    public EdgeCasesView() {
        add(new H2("Edge cases"),
                new Paragraph("Each button starts a UI fiber; the log below says what it did. Open this"
                        + " page in a second tab too: the log is the session's, so it shows in both."));
        final Button doubleClick = new Button("Save (double-click me)");
        doubleClick.addClickListener(e -> {
            log.add("Double-click: a click started a UI fiber");
            UIFibers.runLater(() -> Dialogs.confirm("Saved once: the second click found this dialog open and was dropped."));
        });
        final FlexLayout buttons = new FlexLayout(
                new Button("Escape without an answer", e -> UIFibers.runLater(this::escapeUnanswered)),
                new Button("F5 with a dialog open", e -> UIFibers.runLater(this::endsOnF5)),
                new Button("Close the tab with a dialog open", e -> UIFibers.runLater(this::tabClose)),
                new Button("Throw after the dialog", e -> UIFibers.runLater(EdgeCasesView::throwAfterDialog)),
                new Button("Two UI fibers at once", e -> UIFibers.runLater(this::twoUIFibers)),
                new Button("Which thread?", e -> UIFibers.runLater(this::whichThread)),
                doubleClick);
        buttons.setFlexWrap(FlexLayout.FlexWrap.WRAP);
        buttons.getStyle().set("gap", "var(--lumo-space-s)");
        add(buttons);
        add(new TryThis("Escape without an answer: press Escape - the wait dies, and the UI fiber ends quietly.",
                "F5 with a dialog open: press F5 - the reload closes this UI, so the wait dies; the"
                        + " F5 page shows the other way.",
                "Close the tab with a dialog open, from a second tab: the log shows its finally ran.",
                "Throw after the dialog: the exception reaches the session's ErrorHandler.",
                "Double-click Save: one dialog, one log line."));
        add(new H3("What the UI fibers did"), new FiberLogList());
    }

    private void escapeUnanswered() {
        final CompletableFuture<Boolean> ok = new CompletableFuture<>();
        final Dialog dialog = new Dialog(new Paragraph("Press Escape, or click outside: no listener answers that."));
        dialog.setHeaderTitle("Escape");
        dialog.getFooter().add(new Button("OK", e -> ok.complete(true)));
        try {
            BlockingDialogs.showAndAwait(dialog, ok);
            log.add("Escape: answered OK");
        } catch (WaitDiedException e) {
            log.add("Escape: the dialog closed unanswered, so its wait died");
            throw e;  // let it escape: the UI fiber ends quietly
        } finally {
            log.add("Escape: finally ran");
        }
    }

    private void endsOnF5() {
        try {
            Dialogs.confirm("Press F5 now: this page is not @PreserveOnRefresh, so the reload ends this UI fiber.");
            log.add("F5 here: answered instead");
        } finally {
            log.add("F5 here: finally ran");
        }
    }

    private void tabClose() {
        try {
            Dialogs.confirm("Close this browser tab now, and watch the log in another tab of this page.");
            log.add("Tab close: answered instead");
        } finally {
            log.add("Tab close: finally ran");
        }
    }

    private static void throwAfterDialog() {
        if (Dialogs.confirm("Throw an exception now?")) {
            throw new IllegalStateException("Thrown by a UI fiber after its dialog");
        }
    }

    private void twoUIFibers() {
        UIFibers.runLater(() -> log.add("Two UI fibers: the inner one got " + yesNo(Dialogs.confirm(
                "Inner UI fiber, started by the outer one: I'm on top, answer me first."))));
        log.add("Two UI fibers: the outer one got " + yesNo(Dialogs.confirm("Outer UI fiber")));
    }

    private void whichThread() {
        final Thread before = Thread.currentThread();
        Dialogs.confirm("This UI fiber runs on " + before + ". Park it on this dialog?");
        log.add(Thread.currentThread() == before ? "Which thread: parked and resumed on " + before
                : "Which thread: resumed on another thread, " + Thread.currentThread());
    }

    private static String yesNo(boolean yes) {
        return yes ? "Yes" : "No";
    }
}
