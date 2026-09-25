/*
 * Copyright 2026 Martin Vysny
 *
 * Licensed under the Apache License, Version 2.0. See the LICENSE file in the
 * project root for the full license text.
 */
package com.example.blockingdialogs;

import com.vaadin.flow.function.SerializableConsumer;
import com.vaadin.flow.server.VaadinSession;
import com.vaadin.flow.shared.Registration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * What the edge-case UI fibers did, one line per step, per session - so a UI fiber ending in a
 * closed tab still shows in the session's other tabs, through {@link FiberLogList}. Touched only
 * holding the session lock.
 */
public final class FiberLog implements Serializable {
    private static final Logger log = LoggerFactory.getLogger(FiberLog.class);

    private final List<String> entries = new ArrayList<>();
    private final List<SerializableConsumer<String>> listeners = new ArrayList<>();

    private FiberLog() {
    }

    /**
     * @return the log of the current session, created on first use.
     */
    public static FiberLog current() {
        final VaadinSession session = Objects.requireNonNull(VaadinSession.getCurrent(), "no current session");
        FiberLog fiberLog = session.getAttribute(FiberLog.class);
        if (fiberLog == null) {
            fiberLog = new FiberLog();
            session.setAttribute(FiberLog.class, fiberLog);
        }
        return fiberLog;
    }

    public void add(String entry) {
        log.info(entry);
        entries.add(entry);
        List.copyOf(listeners).forEach(listener -> listener.accept(entry));
    }

    public List<String> entries() {
        return List.copyOf(entries);
    }

    /**
     * @param listener gets every entry added from now on.
     */
    public Registration addListener(SerializableConsumer<String> listener) {
        listeners.add(listener);
        return () -> listeners.remove(listener);
    }
}
