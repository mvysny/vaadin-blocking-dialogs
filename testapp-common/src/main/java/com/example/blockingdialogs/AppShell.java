/*
 * Copyright 2026 Martin Vysny
 *
 * Licensed under the MIT License. See the LICENSE file in the project root
 * for the full license text.
 */
package com.example.blockingdialogs;

import com.vaadin.flow.component.dependency.StyleSheet;
import com.vaadin.flow.component.page.AppShellConfigurator;
import com.vaadin.flow.component.page.Push;
import com.vaadin.flow.theme.lumo.Lumo;

/**
 * {@code @Push} carries the dialog to the browser while the UI fiber is parked. Here rather than in
 * each app, so that no app can forget it.
 */
@Push
@StyleSheet(Lumo.STYLESHEET)
public class AppShell implements AppShellConfigurator {
}
