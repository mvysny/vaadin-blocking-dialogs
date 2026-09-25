/*
 * Copyright 2026 Martin Vysny
 *
 * Licensed under the MIT License. See the LICENSE file in the project root
 * for the full license text.
 */
package com.example.blockingdialogs;

import com.vaadin.flow.component.AttachEvent;
import com.vaadin.flow.component.grid.Grid;

/**
 * The files of the session's {@link FileStore}. Fetches them on attach; call {@link #refresh()}
 * after changing the store.
 */
public class FileList extends Grid<FileStore.StoredFile> {
    public FileList() {
        addColumn(FileStore.StoredFile::name).setHeader("File");
        addColumn(FileStore.StoredFile::length).setHeader("Characters");
        setAllRowsVisible(true);
        setWidth("24em");
    }

    public void refresh() {
        setItems(FileStore.current().list());
    }

    @Override
    protected void onAttach(AttachEvent attachEvent) {
        super.onAttach(attachEvent);
        refresh();
    }
}
