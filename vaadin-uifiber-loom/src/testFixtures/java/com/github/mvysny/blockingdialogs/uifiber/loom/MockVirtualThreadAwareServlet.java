/*
 * Copyright 2026 Martin Vysny
 *
 * Licensed under the MIT License. See the LICENSE file in the project root
 * for the full license text.
 */
package com.github.mvysny.blockingdialogs.uifiber.loom;

import com.github.mvysny.kaributesting.v10.Routes;
import com.github.mvysny.kaributesting.v10.mock.MockService;
import com.github.mvysny.kaributesting.v10.mock.MockVaadinServlet;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.function.DeploymentConfiguration;
import com.vaadin.flow.server.ServiceException;
import com.vaadin.flow.server.VaadinServlet;
import com.vaadin.flow.server.VaadinServletService;
import com.vaadin.flow.server.WrappedSession;
import kotlin.jvm.functions.Function0;

import java.util.concurrent.locks.Lock;

/**
 * Karibu's counterpart of {@link LoomVaadinServlet}, which the loom strategy needs:
 * <pre>{@code
 * MockVaadin.setup(MockedUI::new, new MockVirtualThreadAwareServlet(routes));
 * }</pre>
 */
public class MockVirtualThreadAwareServlet extends MockVaadinServlet {
    public MockVirtualThreadAwareServlet(Routes routes) {
        super(routes);
    }

    @Override
    protected VaadinServletService createServletService(DeploymentConfiguration deploymentConfiguration) {
        final VaadinServletService service = new Service(this, deploymentConfiguration, getUiFactory());
        try {
            service.init();
        } catch (ServiceException e) {
            throw new RuntimeException(e);
        }
        getRoutes().register(service.getContext());
        return service;
    }

    private static class Service extends MockService {
        Service(VaadinServlet servlet, DeploymentConfiguration deploymentConfiguration,
                Function0<? extends UI> uiFactory) {
            super(servlet, deploymentConfiguration, uiFactory);
        }

        @Override
        protected Lock getSessionLock(WrappedSession wrappedSession) {
            return VirtualThreadAwareLock.wrap(this, wrappedSession, super.getSessionLock(wrappedSession));
        }
    }
}
