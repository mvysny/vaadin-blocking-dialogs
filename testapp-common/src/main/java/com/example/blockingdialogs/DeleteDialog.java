/*
 * Copyright 2026 Martin Vysny
 *
 * Licensed under the Apache License, Version 2.0. See the LICENSE file in the
 * project root for the full license text.
 */
package com.example.blockingdialogs;

import com.vaadin.flow.component.ComponentEvent;
import com.vaadin.flow.component.ComponentEventListener;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.shared.Registration;

/**
 * "Delete <i>file</i>?" with Yes / No / Yes to all / Cancel - one button more than a
 * {@code ConfirmDialog} has. Answers through {@link #addAnswerListener}, Escape as Cancel; stays
 * open, the listener closes it.
 */
public class DeleteDialog extends Dialog {
    public enum Answer {YES, NO, YES_TO_ALL, CANCEL}

    public static class AnswerEvent extends ComponentEvent<DeleteDialog> {
        private final Answer answer;

        AnswerEvent(DeleteDialog source, Answer answer) {
            super(source, true);
            this.answer = answer;
        }

        public Answer getAnswer() {
            return answer;
        }
    }

    public DeleteDialog(String fileName) {
        setHeaderTitle("Delete " + fileName + "?");
        final Button yes = button("Yes", Answer.YES);
        yes.addThemeVariants(ButtonVariant.PRIMARY);
        getFooter().add(button("Cancel", Answer.CANCEL), button("Yes to all", Answer.YES_TO_ALL),
                button("No", Answer.NO), yes);
        addDialogCloseActionListener(e -> answer(Answer.CANCEL));
    }

    public Registration addAnswerListener(ComponentEventListener<AnswerEvent> listener) {
        return addListener(AnswerEvent.class, listener);
    }

    private Button button(String text, Answer answer) {
        return new Button(text, e -> answer(answer));
    }

    private void answer(Answer answer) {
        fireEvent(new AnswerEvent(this, answer));
    }
}
