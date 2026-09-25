/*
 * Copyright 2026 Martin Vysny
 *
 * Licensed under the MIT License. See the LICENSE file in the project root
 * for the full license text.
 */
package com.github.mvysny.blockingdialogs.uifiber.loom;

import com.vaadin.flow.server.ServiceInitEvent;
import com.vaadin.flow.server.VaadinServiceInitListener;

/**
 * Fails the first session whose lock isn't a {@link VirtualThreadAwareLock}, naming the fix - at
 * session init, rather than at the first UI fiber, long after the app looked fine. Found by Vaadin
 * through {@code META-INF/services}; the app never registers it.
 */
public final class SessionLockCheck implements VaadinServiceInitListener {
    @Override
    public void serviceInit(ServiceInitEvent event) {
        event.getSource().addSessionInitListener(e ->
                VirtualThreadAwareLock.asVirtualThreadAware(e.getSession().getLockInstance()));
    }
}
