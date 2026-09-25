/*
 * Copyright 2026 Martin Vysny
 *
 * Licensed under the Apache License, Version 2.0. See the LICENSE file in the
 * project root for the full license text.
 */
package com.example.blockingdialogs;

import com.github.mvysny.kaributesting.v10.MockVaadin;
import com.github.mvysny.kaributesting.v10.Routes;
import com.github.mvysny.kaributesting.v10.mock.MockVaadinServlet;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.UI;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.github.mvysny.kaributesting.v10.LocatorJ._assertOne;

/**
 * Every test of the demo, run on the runner of the app that extends it - once per runner, from one
 * source:
 * <pre>{@code
 * class LoomDemoTest extends DemoTest {
 *     @Override
 *     protected MockVaadinServlet createServlet(Routes routes) {
 *         return new MockVirtualThreadAwareServlet(routes);
 *     }
 * }
 * }</pre>
 * Each scenario's tests are an abstract class of their own, run here as a {@code @Nested} inner
 * class; this class's {@code @BeforeEach} sets Vaadin up for them too. Errors reaching the session's
 * {@code ErrorHandler} fail the test, unless it takes them with {@link DemoTesting#takeErrors()}.
 */
public abstract class DemoTest {
    private static Routes routes;

    /**
     * @return the Karibu servlet the app's runner needs.
     */
    protected abstract MockVaadinServlet createServlet(Routes routes);

    @BeforeAll
    public static void discoverRoutes() {
        routes = new Routes().autoDiscoverViews("com.example.blockingdialogs");
    }

    @BeforeEach
    public void setupVaadin() {
        final MockVaadinServlet servlet = createServlet(routes);
        MockVaadin.setup(servlet.getUiFactory(), servlet);
        DemoTesting.recordErrors();
    }

    @AfterEach
    public void tearDownVaadin() {
        try {
            DemoTesting.expectNoErrors();
        } finally {
            MockVaadin.tearDown();
        }
    }

    @Test
    public void everyRouteRenders() {
        for (Class<? extends Component> view : List.of(SaveChangesView.class, DeleteFilesView.class,
                ImportView.class, RefreshView.class, EdgeCasesView.class)) {
            UI.getCurrent().navigate(view);
            _assertOne(view);
            _assertOne(PushClock.class);
        }
    }

    @Nested
    public class SaveChangesBlocking extends SaveChangesTests {
        public SaveChangesBlocking() {
            super("Close");
        }
    }

    @Nested
    public class SaveChangesCallbacks extends SaveChangesTests {
        public SaveChangesCallbacks() {
            super("Close (callbacks)");
        }
    }

    @Nested
    public class DeleteFilesBlocking extends DeleteFilesTests {
        public DeleteFilesBlocking() {
            super("Delete all");
        }
    }

    @Nested
    public class DeleteFilesCallbacks extends DeleteFilesTests {
        public DeleteFilesCallbacks() {
            super("Delete all (callbacks)");
        }
    }

    @Nested
    public class Import extends ImportTests {
    }

    @Nested
    public class Refresh extends RefreshTests {
    }

    @Nested
    public class EdgeCases extends EdgeCasesTests {
    }
}
