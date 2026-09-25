/*
 * Copyright 2026 Martin Vysny
 *
 * Licensed under the MIT License. See the LICENSE file in the project root
 * for the full license text.
 */
package com.github.mvysny.blockingdialogs.uifiber.loom;

import com.github.mvysny.blockingdialogs.BlockingDialogs;
import com.github.mvysny.kaributesting.v10.MockVaadin;
import com.github.mvysny.kaributesting.v10.Routes;
import com.github.mvysny.kaributesting.v10.mock.MockedUI;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.confirmdialog.ConfirmDialog;
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
import java.util.concurrent.atomic.AtomicReference;

import static com.github.mvysny.kaributesting.v10.LocatorJ._assertOne;
import static com.github.mvysny.kaributesting.v10.LocatorJ._get;
import static com.github.mvysny.kaributesting.v10.pro.ConfirmDialogKt._fireConfirm;

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
        BlockingDialogs.runUntilPark(() -> log.add("A resumed: " + BlockingDialogs.parkAndAwait(UI.getCurrent(), answer)));
        BlockingDialogs.runUntilPark(() -> answer.complete("yes"));   // the dialog's OK click
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
            BlockingDialogs.runUntilPark(() -> {
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

    /** The inline branch's CurrentInstance restore, after an inline park that followed its dialog across F5. */
    @Test
    public void inlineParkAcrossF5() {
        UI.getCurrent().navigate(TestViews.PreservedView.class);
        final UI oldUI = UI.getCurrent();
        final AtomicReference<UI> insideAfterPark = new AtomicReference<>();
        final AtomicReference<UI> outerAfterInline = new AtomicReference<>();
        BlockingDialogs.runLater(() -> {
            BlockingDialogs.runUntilPark(() -> {
                final ConfirmDialog dialog = new ConfirmDialog();
                dialog.setText("Sure?");
                BlockingDialogs.showAndAwait(dialog);
                insideAfterPark.set(UI.getCurrent());
            });
            outerAfterInline.set(UI.getCurrent());
        });
        _assertOne(ConfirmDialog.class);
        UI.getCurrent().getPage().reload();
        final UI newUI = UI.getCurrent();
        _fireConfirm(_get(ConfirmDialog.class));
        MockVaadin.clientRoundtrip();
        System.out.println("PROBE F5 inline: inner UI fiber after park sees " + name(insideAfterPark.get(), oldUI, newUI)
                + ", outer UI fiber after runUntilPark sees " + name(outerAfterInline.get(), oldUI, newUI));
    }

    private static String name(UI ui, UI oldUI, UI newUI) {
        return ui == newUI ? "the NEW UI" : ui == oldUI ? "the OLD (closed) UI" : String.valueOf(ui);
    }
}
