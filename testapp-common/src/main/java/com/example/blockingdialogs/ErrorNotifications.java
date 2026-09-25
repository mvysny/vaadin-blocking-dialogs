/*
 * Copyright 2026 Martin Vysny
 *
 * Licensed under the Apache License, Version 2.0. See the LICENSE file in the
 * project root for the full license text.
 */
package com.example.blockingdialogs;

import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.notification.NotificationVariant;
import com.vaadin.flow.server.ErrorEvent;
import com.vaadin.flow.server.ErrorHandler;
import com.vaadin.flow.server.ServiceInitEvent;
import com.vaadin.flow.server.VaadinServiceInitListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Gives every session an {@link ErrorHandler} that shows the error as a notification: a UI fiber's
 * exception goes to that handler, and Vaadin's default one only logs it. Registered in
 * {@code META-INF/services}.
 */
public class ErrorNotifications implements VaadinServiceInitListener {
    private static final Logger log = LoggerFactory.getLogger(ErrorNotifications.class);

    @Override
    public void serviceInit(ServiceInitEvent event) {
        event.getSource().addSessionInitListener(e -> e.getSession().setErrorHandler(ErrorNotifications::show));
    }

    private static void show(ErrorEvent event) {
        final Throwable t = event.getThrowable();
        log.error("Unhandled error", t);
        if (UI.getCurrent() != null) {
            Notification.show("Error: " + t.getMessage()).addThemeVariants(NotificationVariant.ERROR);
        }
    }
}
