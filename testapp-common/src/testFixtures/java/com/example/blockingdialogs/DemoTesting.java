/*
 * Copyright 2026 Martin Vysny
 *
 * Licensed under the MIT License. See the LICENSE file in the project root
 * for the full license text.
 */
package com.example.blockingdialogs;

import com.github.mvysny.kaributesting.v10.MockVaadin;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.confirmdialog.ConfirmDialog;
import com.vaadin.flow.component.html.ListItem;
import com.vaadin.flow.server.ErrorHandler;
import com.vaadin.flow.server.VaadinSession;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import static com.github.mvysny.kaributesting.v10.LocatorJ._click;
import static com.github.mvysny.kaributesting.v10.LocatorJ._find;
import static com.github.mvysny.kaributesting.v10.LocatorJ._get;
import static com.github.mvysny.kaributesting.v10.pro.ConfirmDialogKt._getText;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * The lookups and waits the scenario tests share.
 */
public final class DemoTesting {
    private DemoTesting() {
    }

    /**
     * What reached the session's {@code ErrorHandler} since the test started.
     */
    private static final List<Throwable> errors = new ArrayList<>();

    /**
     * Wraps the app's own {@code ErrorHandler}, so that it still shows the error.
     */
    static void recordErrors() {
        errors.clear();
        final VaadinSession session = VaadinSession.getCurrent();
        final ErrorHandler app = session.getErrorHandler();
        session.setErrorHandler(event -> {
            errors.add(event.getThrowable());
            app.error(event);
        });
    }

    /**
     * @return the errors so far, which then no longer fail the test.
     */
    public static List<Throwable> takeErrors() {
        final List<Throwable> taken = List.copyOf(errors);
        errors.clear();
        return taken;
    }

    static void expectNoErrors() {
        assertEquals(List.of(), takeErrors(), "errors reached the ErrorHandler");
    }

    public static void clickButton(String text) {
        _click(_get(Button.class, spec -> spec.withText(text)));
    }

    public static void clickButton(Component in, String text) {
        _click(_get(in, Button.class, spec -> spec.withText(text)));
    }

    /**
     * @return the one open confirm dialog whose text starts with {@code textStart}.
     */
    public static ConfirmDialog confirmDialog(String textStart) {
        return _get(ConfirmDialog.class, spec -> spec.withPredicate(dialog -> _getText(dialog).startsWith(textStart)));
    }

    /**
     * Waits for the UI fibers and background threads: runs client round trips until {@code condition}
     * holds. A wake-up may take several - a dialog removed on one, its detach checked on the next, the
     * UI fiber resumed on a third - and how many is the runner's business.
     *
     * @throws AssertionError after 10 seconds.
     */
    public static void awaitUntil(String what, BooleanSupplier condition) {
        awaitUntil(() -> what, condition);
    }

    /**
     * @param what describes the condition at the timeout, so it can say how far things got.
     */
    public static void awaitUntil(Supplier<String> what, BooleanSupplier condition) {
        final long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (true) {
            MockVaadin.clientRoundtrip();
            if (condition.getAsBoolean()) {
                return;
            }
            if (System.nanoTime() > deadline) {
                fail("Timed out waiting until " + what.get());
            }
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        }
    }

    /**
     * Waits for the session's {@link FiberLog} to read {@code entries}, then checks that the current
     * tab's {@link FiberLogList} shows them.
     */
    public static void expectFiberLog(String... entries) {
        awaitUntil(() -> "the log reads " + List.of(entries) + ", not " + FiberLog.current().entries(),
                () -> FiberLog.current().entries().equals(List.of(entries)));
        assertEquals(List.of(entries), _find(_get(FiberLogList.class), ListItem.class).stream()
                .map(ListItem::getText).toList());
    }
}
