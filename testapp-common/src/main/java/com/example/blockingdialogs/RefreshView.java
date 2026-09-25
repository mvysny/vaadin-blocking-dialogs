/*
 * Copyright 2026 Martin Vysny
 *
 * Licensed under the Apache License, Version 2.0. See the LICENSE file in the
 * project root for the full license text.
 */
package com.example.blockingdialogs;

import com.github.mvysny.blockingdialogs.UIFibers;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.html.H2;
import com.vaadin.flow.component.html.H3;
import com.vaadin.flow.component.html.Paragraph;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.PreserveOnRefresh;
import com.vaadin.flow.router.Route;

/**
 * F5 with a dialog open, on a {@code @PreserveOnRefresh} route: the dialog moves to the reloaded
 * page, and the UI fiber waiting on it follows to the new UI.
 */
@Route(value = "f5", layout = MainLayout.class)
@PageTitle("F5")
@PreserveOnRefresh
public class RefreshView extends VerticalLayout {
    public RefreshView() {
        add(new H2("F5"),
                new Paragraph("This page is @PreserveOnRefresh: a reload keeps it, and an open dialog with it."
                        + " The UI fiber waiting on the dialog carries on in the reloaded page."));
        final Button ask = new Button("Ask, then press F5", e -> UIFibers.runLater(RefreshView::survivesF5));
        ask.addThemeVariants(ButtonVariant.PRIMARY);
        add(ask);
        add(new TryThis("Ask, press F5: the dialog comes back; answer it, and the log says so.",
                "Do the same on the Edge cases page, which isn't preserved: there the reload ends the UI fiber."));
        add(new H3("What the UI fibers did"), new FiberLogList());
    }

    private static void survivesF5() {
        final UI asking = UI.getCurrent();
        final boolean yes = Dialogs.confirm("Press F5 now, then answer.");
        FiberLog.current().add("F5: answered " + (yes ? "Yes" : "No")
                + (UI.getCurrent() == asking ? ", in the same UI" : ", in the reloaded UI"));
    }
}
