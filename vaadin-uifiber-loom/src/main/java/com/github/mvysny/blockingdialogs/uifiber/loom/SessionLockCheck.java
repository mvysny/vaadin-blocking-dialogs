/*
 * Copyright 2026 Martin Vysny
 *
 * Licensed under the Apache License, Version 2.0. See the LICENSE file in the
 * project root for the full license text.
 */
package com.github.mvysny.blockingdialogs.uifiber.loom;

import com.vaadin.flow.server.ServiceInitEvent;
import com.vaadin.flow.server.VaadinServiceInitListener;

/**
 * Fails the first request of every session whose lock isn't a {@link VirtualThreadAwareLock},
 * naming the fix - at session init, rather than at the first UI fiber, long after the app looked
 * fine. Found by Vaadin through {@code META-INF/services}, on Spring too; the app never registers it.
 *
 * @implNote Throws an {@link Error}, since Vaadin hands a session init listener's {@code Exception}
 * to the session's {@code ErrorHandler}, which only logs it, and the session carries on
 * ({@code R_service_init_listeners}).
 */
public final class SessionLockCheck implements VaadinServiceInitListener {
    @Override
    public void serviceInit(ServiceInitEvent event) {
        event.getSource().addSessionInitListener(e -> {
            try {
                VirtualThreadAwareLock.asVirtualThreadAware(e.getSession().getLockInstance());
            } catch (IllegalStateException ex) {
                throw new Error(ex.getMessage(), ex);
            }
        });
    }
}
