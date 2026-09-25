/*
 * Copyright 2026 Martin Vysny
 *
 * Licensed under the MIT License. See the LICENSE file in the project root
 * for the full license text.
 */
package com.example.blockingdialogs;

import com.vaadin.flow.server.VaadinSession;

import java.io.Serializable;
import java.util.List;
import java.util.Objects;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * The demo's files, in memory, one store per session so visitors never meet each other's files:
 * <pre>{@code
 * FileStore store = FileStore.current();
 * if (!store.exists("notes.txt") || Dialogs.confirm("notes.txt exists. Overwrite?")) {
 *     store.save("notes.txt", text);
 * }
 * }</pre>
 * Starts with the {@link #SEEDED} files, so that "File exists, overwrite?" is reachable at once.
 * Touched only holding the session lock.
 */
public final class FileStore implements Serializable {
    public static final List<String> SEEDED = List.of("draft.txt", "ideas.txt", "notes.txt", "shopping.txt", "todo.txt");

    /**
     * One stored file.
     *
     * @param length in characters.
     */
    public record StoredFile(String name, int length) implements Serializable {
    }

    private final SortedMap<String, String> files = new TreeMap<>();

    private FileStore() {
        restore();
    }

    /**
     * @return the store of the current session, created on first use.
     */
    public static FileStore current() {
        final VaadinSession session = Objects.requireNonNull(VaadinSession.getCurrent(), "no current session");
        FileStore store = session.getAttribute(FileStore.class);
        if (store == null) {
            store = new FileStore();
            session.setAttribute(FileStore.class, store);
        }
        return store;
    }

    /**
     * @return the file names, sorted.
     */
    public List<String> names() {
        return List.copyOf(files.keySet());
    }

    public List<StoredFile> list() {
        return files.entrySet().stream().map(e -> new StoredFile(e.getKey(), e.getValue().length())).toList();
    }

    public boolean exists(String name) {
        return files.containsKey(name);
    }

    /**
     * Creates or overwrites {@code name}.
     */
    public void save(String name, String content) {
        files.put(name, content);
    }

    public void delete(String name) {
        files.remove(name);
    }

    /**
     * Throws away every change: the store holds the {@link #SEEDED} files again.
     */
    public void restore() {
        files.clear();
        SEEDED.forEach(name -> files.put(name, "The contents of " + name));
    }
}
