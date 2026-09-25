/*
 * Copyright 2026 Martin Vysny
 *
 * Licensed under the MIT License. See the LICENSE file in the project root
 * for the full license text.
 */
package com.example.blockingdialogs;

import com.vaadin.flow.component.applayout.AppLayout;
import com.vaadin.flow.component.applayout.DrawerToggle;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.sidenav.SideNav;
import com.vaadin.flow.component.sidenav.SideNavItem;

/**
 * The demo's frame: one side-nav item per scenario, and the {@link PushClock} in the header, which
 * keeps ticking while a dialog is open.
 */
public class MainLayout extends AppLayout {
    public MainLayout() {
        final Span title = new Span("Vaadin Blocking Dialogs");
        title.getStyle().set("font-size", "var(--lumo-font-size-l)").set("font-weight", "600");
        final PushClock clock = new PushClock();
        clock.getStyle().set("margin-left", "auto").set("margin-right", "var(--lumo-space-m)")
                .set("font-variant-numeric", "tabular-nums");
        addToNavbar(new DrawerToggle(), title, clock);

        final SideNav nav = new SideNav();
        nav.addItem(new SideNavItem("Save changes?", SaveChangesView.class),
                new SideNavItem("Delete files", DeleteFilesView.class),
                new SideNavItem("Import", ImportView.class),
                new SideNavItem("F5", RefreshView.class),
                new SideNavItem("Edge cases", EdgeCasesView.class));
        addToDrawer(nav);
    }
}
