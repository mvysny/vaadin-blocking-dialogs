/*
 * Copyright 2026 Martin Vysny
 *
 * Licensed under the MIT License. See the LICENSE file in the project root
 * for the full license text.
 */
package com.github.mvysny.blockingdialogs.uifiber.loom;

import com.github.mvysny.blockingdialogs.BlockingDialogs;
import com.github.mvysny.blockingdialogs.UIFibers;
import com.github.mvysny.kaributesting.v10.MockVaadin;
import com.github.mvysny.kaributesting.v10.Routes;
import com.github.mvysny.kaributesting.v10.mock.MockedUI;
import com.vaadin.flow.component.UI;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * PROBE, not a regression test: what {@code runUntilPark} promises versus what it does. Prints
 * {@code PROBE} lines and asserts nothing.
 */
public class RunUntilParkProbeTest {
    private static Routes routes;
    private final List<String> log = new CopyOnWriteArrayList<>();

    @BeforeAll
    public static void discoverRoutes() {
        routes = new Routes().autoDiscoverViews("com.github.mvysny.blockingdialogs.uifiber.loom");
    }

    @BeforeEach
    public void setupVaadin() {
        MockVaadin.setup(MockedUI::new, new MockVirtualThreadAwareServlet(routes));
    }

    @AfterEach
    public void tearDownVaadin() {
        MockVaadin.tearDown();
    }

    /** Does a UI fiber woken by the {@code runUntilPark} UI fiber run before the call returns? */
    @Test
    public void aUIFiberWokenByTheCallIsNotSettledWhenItReturns() {
        final CompletableFuture<String> answer = new CompletableFuture<>();
        UIFibers.runUntilPark(() -> log.add("A resumed: " + UIFibers.parkAndAwait(UI.getCurrent(), answer)));
        UIFibers.runUntilPark(() -> answer.complete("yes"));   // the dialog's OK click
        System.out.println("PROBE woken UI fiber, right after the OK click's runUntilPark: " + log);
        MockVaadin.clientRoundtrip();
        System.out.println("PROBE woken UI fiber, after a roundtrip: " + log);
    }

    /** Does an IO unmount count as "parks"? */
    @Test
    public void ioBeforeTheFirstDialog() throws Exception {
        try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
             Socket client = new Socket(InetAddress.getLoopbackAddress(), server.getLocalPort());
             Socket far = server.accept()) {
            UIFibers.runUntilPark(() -> {
                try {
                    client.getInputStream().read();
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
                log.add("after IO, dialog opened");
            });
            System.out.println("PROBE IO: runUntilPark returned with log=" + log);
            far.getOutputStream().write(1);
            far.getOutputStream().flush();
            for (int i = 0; i < 100 && log.isEmpty(); i++) {
                MockVaadin.clientRoundtrip();
                Thread.sleep(10);
            }
        }
    }
}
