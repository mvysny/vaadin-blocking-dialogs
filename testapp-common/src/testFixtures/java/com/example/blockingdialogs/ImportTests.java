/*
 * Copyright 2026 Martin Vysny
 *
 * Licensed under the Apache License, Version 2.0. See the LICENSE file in the
 * project root for the full license text.
 */
package com.example.blockingdialogs;

import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.progressbar.ProgressBar;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static com.example.blockingdialogs.DemoTesting.awaitUntil;
import static com.example.blockingdialogs.DemoTesting.clickButton;
import static com.github.mvysny.kaributesting.v10.LocatorJ._assertNone;
import static com.github.mvysny.kaributesting.v10.LocatorJ._get;
import static com.github.mvysny.kaributesting.v10.NotificationsKt.expectNotifications;
import static com.github.mvysny.kaributesting.v10.NotificationsKt.getNotifications;

/**
 * {@link ImportView}: a background job behind a progress dialog, and the wrong way.
 */
public abstract class ImportTests {
    @BeforeEach
    public void navigate() {
        UI.getCurrent().navigate(ImportView.class);
    }

    @Test
    public void theImportCompletes() {
        clickButton("Import");
        awaitUntil("the import ends", () -> !getNotifications().isEmpty());
        expectNotifications("Imported " + ImportView.RECORDS + " records");
        _assertNone(ProgressDialog.class);
    }

    @Test
    public void theProgressReachesTheDialog() {
        clickButton("Import");
        awaitUntil("the bar moves", () -> _get(_get(ProgressDialog.class), ProgressBar.class).getValue() > 0);
        clickButton(_get(ProgressDialog.class), "Cancel");
        expectNotifications("Import cancelled");
    }

    @Test
    public void cancelStopsTheImport() {
        clickButton("Import");
        clickButton(_get(ProgressDialog.class), "Cancel");
        expectNotifications("Import cancelled");
        _assertNone(ProgressDialog.class);
    }

    @Test
    public void theWrongWayStillImports() {
        clickButton("Import on the UI fiber (freezes the app)");
        expectNotifications("Imported " + ImportView.RECORDS + " records, the app frozen meanwhile");
    }
}
