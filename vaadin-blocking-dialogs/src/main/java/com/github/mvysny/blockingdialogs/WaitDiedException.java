/*
 * Copyright 2026 Martin Vysny
 *
 * Licensed under the MIT License. See the LICENSE file in the project root
 * for the full license text.
 */
package com.github.mvysny.blockingdialogs;

import org.jspecify.annotations.Nullable;

import java.util.concurrent.CancellationException;

/**
 * A wait nobody will answer any more: its anchor detached for good, or the waiting thread was
 * interrupted. Only this ends a UI fiber quietly; let it escape, and run cleanup in {@code finally}:
 * <pre>{@code
 * UIFibers.runLater(() -> {
 *     try {
 *         if (BlockingDialogs.showAndAwait(dialog) == ConfirmDialogOutcome.CONFIRM) {
 *             delete(file);
 *         }
 *     } finally {
 *         releaseLock(file);   // runs on a tab close too
 *     }
 * }));
 * }</pre>
 *
 * @apiNote A {@link CancellationException} of the app's own - a cancelled future it awaits, a
 * cancelled {@code SwingWorker.get()} - is an error like any other, and goes to the session's
 * {@link com.vaadin.flow.server.ErrorHandler}. A subtype, so {@code catch (CancellationException e)}
 * still catches both. Not an {@link Error}, which a {@code catch (Exception e)} couldn't swallow: an
 * app that wants that converts it at its own dialog seam.
 */
public final class WaitDiedException extends CancellationException {
    WaitDiedException(String message, @Nullable Throwable cause) {
        super(message);
        if (cause != null) {
            initCause(cause);
        }
    }
}
