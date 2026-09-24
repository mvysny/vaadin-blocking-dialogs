/*
 * Copyright 2026 Martin Vysny
 *
 * Licensed under the MIT License. See the LICENSE file in the project root
 * for the full license text.
 */
package com.example.blockingdialogs;

import com.github.mvysny.blockingdialogs.loom.MockVirtualThreadAwareServlet;
import com.github.mvysny.kaributesting.v10.MockVaadin;
import com.github.mvysny.kaributesting.v10.Routes;
import com.github.mvysny.kaributesting.v10.mock.MockedUI;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.confirmdialog.ConfirmDialog;
import com.vaadin.flow.server.VaadinSession;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static com.github.mvysny.kaributesting.v10.LocatorJ.*;
import static com.github.mvysny.kaributesting.v10.NotificationsKt.expectNotifications;
import static com.github.mvysny.kaributesting.v10.pro.ConfirmDialogKt._fireCancel;
import static com.github.mvysny.kaributesting.v10.pro.ConfirmDialogKt._fireConfirm;
import static com.github.mvysny.kaributesting.v10.pro.ConfirmDialogKt._getText;
import static org.junit.jupiter.api.Assertions.assertEquals;

public class MainViewTest {
    private static Routes routes;

    /**
     * Everything handed to the session's error handler.
     */
    private List<Throwable> reportedErrors;

    @BeforeAll
    public static void discoverRoutes() {
        routes = new Routes().autoDiscoverViews("com.example.blockingdialogs");
    }

    @BeforeEach
    public void setupVaadin() {
        MockVaadin.setup(MockedUI::new, new MockVirtualThreadAwareServlet(routes));
        reportedErrors = new ArrayList<>();
        VaadinSession.getCurrent().setErrorHandler(event -> reportedErrors.add(event.getThrowable()));
    }

    @AfterEach
    public void tearDownVaadin() {
        MockVaadin.tearDown();
    }

    @Test
    public void yesAndYes() {
        _click(_get(Button.class, spec -> spec.withText("Blocking dialog")));
        _fireConfirm(confirmDialog("Are you sure?"));
        _fireConfirm(confirmDialog("Are you really sure?"));
        expectNotifications("Yes you're sure");
    }

    @Test
    public void no() {
        _click(_get(Button.class, spec -> spec.withText("Blocking dialog")));
        _fireCancel(confirmDialog("Are you sure?"));
        expectNotifications("Nope, not sure");
    }

    @Test
    public void yesThenNo() {
        _click(_get(Button.class, spec -> spec.withText("Blocking dialog")));
        _fireConfirm(confirmDialog("Are you sure?"));
        _fireCancel(confirmDialog("Are you really sure?"));
        expectNotifications("Yes but no");
    }

    /**
     * The dialog detaches unanswered: the block ends quietly, reporting nothing.
     */
    @Test
    public void dialogDetachedUnanswered() {
        _click(_get(Button.class, spec -> spec.withText("Blocking dialog")));
        final ConfirmDialog dialog = _get(ConfirmDialog.class);

        dialog.removeFromParent();
        MockVaadin.clientRoundtrip(true);

        _assertNone(ConfirmDialog.class);
        expectNotifications();
        assertEquals(List.of(), reportedErrors);
    }

    /**
     * The one open confirm dialog, asking {@code text}.
     */
    private static ConfirmDialog confirmDialog(String text) {
        return _get(ConfirmDialog.class, spec -> spec.withPredicate(dialog -> text.equals(_getText(dialog))));
    }
}
