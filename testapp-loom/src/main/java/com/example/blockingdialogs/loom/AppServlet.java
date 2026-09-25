/*
 * Copyright 2026 Martin Vysny
 *
 * Licensed under the Apache License, Version 2.0. See the LICENSE file in the
 * project root for the full license text.
 */
package com.example.blockingdialogs.loom;

import com.github.mvysny.blockingdialogs.uifiber.loom.LoomVaadinServlet;
import jakarta.servlet.annotation.WebServlet;

@WebServlet(urlPatterns = "/*", asyncSupported = true)
public class AppServlet extends LoomVaadinServlet {
}
