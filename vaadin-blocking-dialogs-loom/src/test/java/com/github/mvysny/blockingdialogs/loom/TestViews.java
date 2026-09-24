/*
 * Copyright 2026 Martin Vysny
 *
 * Licensed under the MIT License. See the LICENSE file in the project root
 * for the full license text.
 */
package com.github.mvysny.blockingdialogs.loom;

import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.router.PreserveOnRefresh;
import com.vaadin.flow.router.Route;

/**
 * The routes of the tests: somewhere to start, and a preserved view.
 */
public final class TestViews {
    private TestViews() {
    }

    @Route("")
    public static class StartView extends Div {
    }

    @Route("preserved")
    @PreserveOnRefresh
    public static class PreservedView extends Div {
    }
}
