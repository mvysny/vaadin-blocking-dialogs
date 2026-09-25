/*
 * Copyright 2026 Martin Vysny
 *
 * Licensed under the Apache License, Version 2.0. See the LICENSE file in the
 * project root for the full license text.
 */
package com.github.mvysny.blockingdialogs;

import com.vaadin.flow.component.confirmdialog.ConfirmDialog;

/**
 * How the user answered a {@link ConfirmDialog} shown by {@link BlockingDialogs#showAndAwait(ConfirmDialog)}.
 *
 * @apiNote An enum rather than the event object: the three events are no sealed hierarchy, so a
 * {@code switch} over them wouldn't be exhaustive.
 */
public enum ConfirmDialogOutcome {
    /**
     * The Confirm button: {@link ConfirmDialog.ConfirmEvent}.
     */
    CONFIRM,
    /**
     * The Reject button: {@link ConfirmDialog.RejectEvent}.
     */
    REJECT,
    /**
     * The Cancel button or Escape - {@link ConfirmDialog.CancelEvent} - or the dialog closed without
     * any of the three: {@link ConfirmDialog.ClosedEvent}.
     */
    CANCEL
}
