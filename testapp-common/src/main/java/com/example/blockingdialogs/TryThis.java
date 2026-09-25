/*
 * Copyright 2026 Martin Vysny
 *
 * Licensed under the MIT License. See the LICENSE file in the project root
 * for the full license text.
 */
package com.example.blockingdialogs;

import com.vaadin.flow.component.details.Details;
import com.vaadin.flow.component.html.ListItem;
import com.vaadin.flow.component.html.UnorderedList;

/**
 * What to try on a page by hand - the checks Karibu can't make, since it runs no browser.
 */
public class TryThis extends Details {
    public TryThis(String... steps) {
        super("Try this");
        final UnorderedList list = new UnorderedList();
        for (String step : steps) {
            list.add(new ListItem(step));
        }
        add(list);
        setOpened(true);
    }
}
