/*
 * Copyright 2026 Martin Vysny
 *
 * Licensed under the MIT License. See the LICENSE file in the project root
 * for the full license text.
 */
package com.github.mvysny.blockingdialogs.uifiber.loom;

import com.vaadin.flow.function.DeploymentConfiguration;
import com.vaadin.flow.server.VaadinServlet;
import com.vaadin.flow.server.VaadinServletService;
import com.vaadin.flow.server.WrappedSession;

import java.util.concurrent.locks.Lock;

/**
 * The service of {@link LoomVaadinServlet}: its session lock is a {@link VirtualThreadAwareLock}.
 * Subclass it to customize the service further, and return the subclass from the servlet's
 * {@code createServletService()}.
 */
public class LoomVaadinServletService extends VaadinServletService {
    public LoomVaadinServletService(VaadinServlet servlet, DeploymentConfiguration deploymentConfiguration) {
        super(servlet, deploymentConfiguration);
    }

    @Override
    protected Lock getSessionLock(WrappedSession wrappedSession) {
        return VirtualThreadAwareLock.wrap(this, wrappedSession, super.getSessionLock(wrappedSession));
    }
}
