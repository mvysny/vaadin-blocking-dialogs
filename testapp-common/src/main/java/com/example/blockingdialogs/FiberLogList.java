/*
 * Copyright 2026 Martin Vysny
 *
 * Licensed under the MIT License. See the LICENSE file in the project root
 * for the full license text.
 */
package com.example.blockingdialogs;

import com.vaadin.flow.component.AttachEvent;
import com.vaadin.flow.component.DetachEvent;
import com.vaadin.flow.component.html.ListItem;
import com.vaadin.flow.component.html.OrderedList;
import com.vaadin.flow.shared.Registration;
import org.jspecify.annotations.Nullable;

/**
 * The session's {@link FiberLog}, live: an entry added from any tab of the session shows here too.
 */
public class FiberLogList extends OrderedList {
    @Nullable
    private Registration listening;

    @Override
    protected void onAttach(AttachEvent attachEvent) {
        super.onAttach(attachEvent);
        removeAll();
        final FiberLog fiberLog = FiberLog.current();
        fiberLog.entries().forEach(this::show);
        listening = fiberLog.addListener(this::show);
    }

    @Override
    protected void onDetach(DetachEvent detachEvent) {
        super.onDetach(detachEvent);
        if (listening != null) {
            listening.remove();
            listening = null;
        }
    }

    private void show(String entry) {
        add(new ListItem(entry));
    }
}
