/*
 * Copyright 2026 Martin Vysny
 *
 * Licensed under the MIT License. See the LICENSE file in the project root
 * for the full license text.
 */
package com.github.mvysny.blockingdialogs.loom;

import com.vaadin.flow.function.DeploymentConfiguration;
import com.vaadin.flow.server.ServiceException;
import com.vaadin.flow.server.VaadinServlet;
import com.vaadin.flow.server.VaadinServletService;

/**
 * A {@link VaadinServlet} with the session lock the loom strategy needs, {@link VirtualThreadAwareLock}.
 * It carries no {@code @WebServlet}, so it never registers itself; the app subclasses it:
 * <pre>{@code
 * @WebServlet(urlPatterns = "/*", asyncSupported = true)
 * public class AppServlet extends LoomVaadinServlet {
 * }
 * }</pre>
 * An app with a service class of its own - Spring's {@code SpringVaadinServletService} - routes its
 * {@code getSessionLock()} through {@link VirtualThreadAwareLock#wrap} instead.
 */
public class LoomVaadinServlet extends VaadinServlet {
    @Override
    protected VaadinServletService createServletService(DeploymentConfiguration deploymentConfiguration)
            throws ServiceException {
        final LoomVaadinServletService service = new LoomVaadinServletService(this, deploymentConfiguration);
        service.init();
        return service;
    }
}
