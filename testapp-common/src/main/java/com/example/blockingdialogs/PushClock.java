/*
 * Copyright 2026 Martin Vysny
 *
 * Licensed under the Apache License, Version 2.0. See the LICENSE file in the
 * project root for the full license text.
 */
package com.example.blockingdialogs;

import com.vaadin.flow.component.AttachEvent;
import com.vaadin.flow.component.DetachEvent;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.html.Span;
import org.jspecify.annotations.Nullable;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * The time of day, ticked every second by push from a background thread. It ticks only while the
 * session lock is free: a parked UI fiber keeps it ticking, a UI fiber busy on the lock freezes it.
 *
 * @implNote One ticker thread for every clock: {@link UI#access} only queues the tick when the lock
 * is taken, so a frozen session never holds up the other sessions' clocks.
 */
public class PushClock extends Span {
    private static final ScheduledExecutorService ticker = Executors.newSingleThreadScheduledExecutor(runnable -> {
        final Thread thread = new Thread(runnable, "push-clock");
        thread.setDaemon(true);
        return thread;
    });
    private static final DateTimeFormatter format = DateTimeFormatter.ofPattern("HH:mm:ss");

    @Nullable
    private transient ScheduledFuture<?> ticking;

    @Override
    protected void onAttach(AttachEvent attachEvent) {
        super.onAttach(attachEvent);
        final UI ui = attachEvent.getUI();
        tick();
        ticking = ticker.scheduleAtFixedRate(() -> ui.access(this::tick), 1, 1, TimeUnit.SECONDS);
    }

    @Override
    protected void onDetach(DetachEvent detachEvent) {
        super.onDetach(detachEvent);
        if (ticking != null) {
            ticking.cancel(false);
            ticking = null;
        }
    }

    private void tick() {
        setText(LocalTime.now().format(format));
    }
}
