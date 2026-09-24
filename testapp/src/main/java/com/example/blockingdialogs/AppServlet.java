/*
 * Copyright 2026 Martin Vysny
 *
 * Licensed under the MIT License. See the LICENSE file in the project root
 * for the full license text.
 */
package com.example.blockingdialogs;

import com.github.mvysny.blockingdialogs.loom.LoomVaadinServlet;
import jakarta.servlet.annotation.WebServlet;

@WebServlet(urlPatterns = "/*", asyncSupported = true)
public class AppServlet extends LoomVaadinServlet {
}
