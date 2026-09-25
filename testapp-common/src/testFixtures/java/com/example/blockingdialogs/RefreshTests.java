/*
 * Copyright 2026 Martin Vysny
 *
 * Licensed under the MIT License. See the LICENSE file in the project root
 * for the full license text.
 */
package com.example.blockingdialogs;

import com.vaadin.flow.component.UI;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static com.example.blockingdialogs.DemoTesting.clickButton;
import static com.example.blockingdialogs.DemoTesting.confirmDialog;
import static com.example.blockingdialogs.DemoTesting.expectFiberLog;
import static com.github.mvysny.kaributesting.v10.pro.ConfirmDialogKt._fireConfirm;

/**
 * {@link RefreshView}: F5 on a {@code @PreserveOnRefresh} route keeps the dialog and its UI fiber.
 */
public abstract class RefreshTests {
    @BeforeEach
    public void navigate() {
        UI.getCurrent().navigate(RefreshView.class);
    }

    @Test
    public void theDialogSurvivesF5() {
        clickButton("Ask, then press F5");
        confirmDialog("Press F5 now");
        UI.getCurrent().getPage().reload();
        _fireConfirm(confirmDialog("Press F5 now"));
        expectFiberLog("F5: answered Yes, in the reloaded UI");
    }

    @Test
    public void withoutF5TheUIStaysTheSame() {
        clickButton("Ask, then press F5");
        _fireConfirm(confirmDialog("Press F5 now"));
        expectFiberLog("F5: answered Yes, in the same UI");
    }
}
