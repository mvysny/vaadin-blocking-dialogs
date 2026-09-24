/*
 * Copyright 2026 Martin Vysny
 *
 * Licensed under the MIT License. See the LICENSE file in the project root
 * for the full license text.
 */
package com.example.blockingdialogs;

import com.github.mvysny.vaadinboot.VaadinBoot;
import org.jetbrains.annotations.NotNull;

/**
 * Run {@link #main(String[])} to launch the demo in embedded Jetty.
 */
public final class Main {
    public static void main(@NotNull String[] args) throws Exception {
        new VaadinBoot()
                .useVirtualThreadsIfAvailable(false)  // the loom strategy needs platform request threads
                .run();
    }
}
