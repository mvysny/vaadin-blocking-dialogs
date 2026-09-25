/*
 * Copyright 2026 Martin Vysny
 *
 * Licensed under the Apache License, Version 2.0. See the LICENSE file in the
 * project root for the full license text.
 */
package com.example.blockingdialogs;

import com.vaadin.flow.component.details.Details;
import com.vaadin.flow.component.html.Pre;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Shows a region of a demo class's own source - the code that actually runs, never a copy that
 * drifts. The region is marked in the source:
 * <pre>{@code
 * // demo:begin blocking
 * private void close() { ... }
 * // demo:end blocking
 * }</pre>
 * {@code new SourceCode("Blocking", SaveChangesView.class, "blocking")} then shows it; several
 * regions of one name show as one, separated by a blank line.
 *
 * @implNote testapp-common's {@code processResources} copies its sources under
 * {@code demo-sources/} on the classpath.
 */
public class SourceCode extends Details {
    /**
     * @throws IllegalStateException if the class's source has no such region.
     */
    public SourceCode(String title, Class<?> source, String region) {
        super(title);
        final Pre pre = new Pre(region(read(source), region));
        pre.getStyle().set("font-size", "var(--lumo-font-size-s)").set("overflow-x", "auto").set("margin", "0");
        add(pre);
        setOpened(true);
        getStyle().set("flex", "1 1 32em").set("min-width", "0");
    }

    private static String read(Class<?> source) {
        final String path = "/demo-sources/" + source.getName().replace('.', '/') + ".java";
        try (InputStream in = source.getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalStateException(path + " is not on the classpath");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static String region(String source, String region) {
        final List<String> segments = new ArrayList<>();
        List<String> segment = null;
        for (String line : source.split("\n")) {
            final String marker = line.strip();
            if (marker.equals("// demo:begin " + region)) {
                segment = new ArrayList<>();
            } else if (marker.equals("// demo:end " + region) && segment != null) {
                segments.add(dedent(segment));
                segment = null;
            } else if (segment != null) {
                segment.add(line);
            }
        }
        if (segments.isEmpty()) {
            throw new IllegalStateException("No region '" + region + "' in the source");
        }
        return String.join("\n\n", segments);
    }

    private static String dedent(List<String> lines) {
        final int indent = lines.stream().filter(line -> !line.isBlank())
                .mapToInt(line -> line.length() - line.stripLeading().length()).min().orElse(0);
        return String.join("\n", lines.stream().map(line -> line.isBlank() ? "" : line.substring(indent)).toList());
    }
}
