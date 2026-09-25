/*
 * Copyright 2026 Martin Vysny
 *
 * Licensed under the Apache License, Version 2.0. See the LICENSE file in the
 * project root for the full license text.
 */
package com.example.blockingdialogs.loom;

import com.example.blockingdialogs.DemoTest;
import com.github.mvysny.blockingdialogs.uifiber.loom.MockVirtualThreadAwareServlet;
import com.github.mvysny.kaributesting.v10.Routes;
import com.github.mvysny.kaributesting.v10.mock.MockVaadinServlet;

/**
 * The demo's tests, on the loom runner.
 */
public class LoomDemoTest extends DemoTest {
    @Override
    protected MockVaadinServlet createServlet(Routes routes) {
        return new MockVirtualThreadAwareServlet(routes);
    }
}
