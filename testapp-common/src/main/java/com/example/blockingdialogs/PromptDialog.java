/*
 * Copyright 2026 Martin Vysny
 *
 * Licensed under the Apache License, Version 2.0. See the LICENSE file in the
 * project root for the full license text.
 */
package com.example.blockingdialogs;

import com.vaadin.flow.component.ComponentEvent;
import com.vaadin.flow.component.ComponentEventListener;
import com.vaadin.flow.component.Key;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.shared.Registration;
import org.jspecify.annotations.Nullable;

/**
 * Asks for one line of text. Answers once, through {@link #addAnswerListener}: OK with the text,
 * Cancel and Escape with {@code null}. Stays open; the listener closes it. {@link Dialogs#prompt}
 * is the blocking form.
 */
public class PromptDialog extends Dialog {
    /**
     * @see #getValue()
     */
    public static class AnswerEvent extends ComponentEvent<PromptDialog> {
        @Nullable
        private final String value;

        AnswerEvent(PromptDialog source, @Nullable String value) {
            super(source, true);
            this.value = value;
        }

        /**
         * @return the text entered, or {@code null} if the user cancelled.
         */
        public @Nullable String getValue() {
            return value;
        }
    }

    public PromptDialog(String label, String initialValue) {
        final TextField field = new TextField(label, initialValue, "");
        field.setWidthFull();
        field.setAutofocus(true);
        add(field);
        final Button ok = new Button("OK", e -> answer(field.getValue()));
        ok.addThemeVariants(ButtonVariant.PRIMARY);
        ok.addClickShortcut(Key.ENTER);
        getFooter().add(new Button("Cancel", e -> answer(null)), ok);
        addDialogCloseActionListener(e -> answer(null));
    }

    public Registration addAnswerListener(ComponentEventListener<AnswerEvent> listener) {
        return addListener(AnswerEvent.class, listener);
    }

    private void answer(@Nullable String value) {
        fireEvent(new AnswerEvent(this, value));
    }
}
