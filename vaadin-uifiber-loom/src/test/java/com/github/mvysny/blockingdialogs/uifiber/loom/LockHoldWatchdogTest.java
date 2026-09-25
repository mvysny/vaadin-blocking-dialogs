/*
 * Copyright 2026 Martin Vysny
 *
 * Licensed under the MIT License. See the LICENSE file in the project root
 * for the full license text.
 */
package com.github.mvysny.blockingdialogs.uifiber.loom;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The WARNs are slf4j-simple's, which writes to whatever {@code System.err} is at the time.
 */
public class LockHoldWatchdogTest {
    private final ByteArrayOutputStream stderr = new ByteArrayOutputStream();
    private final PrintStream originalStderr = System.err;
    private final CountDownLatch release = new CountDownLatch(1);
    private Thread fiber;

    @BeforeEach
    public void parkAFiber() {
        // our own scheduler, as a UI fiber's: start() runs the first segment right here, until the park
        fiber = LoomUtils.newVirtualThread(Runnable::run, "test-ui-fiber", this::parkedHere);
        fiber.start();
        System.setErr(new PrintStream(stderr, true, StandardCharsets.UTF_8));
    }

    @AfterEach
    public void releaseTheFiber() {
        System.setErr(originalStderr);
        release.countDown();
    }

    private void parkedHere() {
        try {
            release.await();
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    private String stderr() {
        return stderr.toString(StandardCharsets.UTF_8);
    }

    /**
     * Offers {@code continuation} from another thread in {@code delay}.
     */
    private static void offerIn(LockHoldWatchdog watchdog, Duration delay, Runnable continuation) {
        Thread.ofPlatform().start(() -> {
            try {
                Thread.sleep(delay);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
            watchdog.offer(continuation);
        });
    }

    @Test
    public void aQuickTakeStaysSilent() {
        final LockHoldWatchdog watchdog = new LockHoldWatchdog(fiber, Duration.ofSeconds(10));
        final Runnable continuation = () -> {};
        watchdog.offer(continuation);
        assertSame(continuation, watchdog.take());
        assertEquals("", stderr());
    }

    @Test
    public void aLongTakeWarnsWithTheFibersStackBackingOffThenLogsTheResume() {
        final LockHoldWatchdog watchdog = new LockHoldWatchdog(fiber, Duration.ofMillis(100));
        final Runnable continuation = () -> {};
        offerIn(watchdog, Duration.ofMillis(450), continuation);   // WARNs at 100 ms and 300 ms, not at 900 ms
        assertSame(continuation, watchdog.take());
        final String out = stderr();
        assertEquals(2, out.split("has held the session lock", -1).length - 1, out);
        assertTrue(out.contains("java.lang.Throwable: test-ui-fiber is here"), out);
        assertTrue(out.contains("LockHoldWatchdogTest.parkedHere"), "the stack of the unmounted fiber: " + out);
        assertTrue(out.contains("UI fiber test-ui-fiber resumed after"), out);
    }

    @Test
    public void zeroDisablesTheWarns() {
        final LockHoldWatchdog watchdog = new LockHoldWatchdog(fiber, Duration.ZERO);
        final Runnable continuation = () -> {};
        offerIn(watchdog, Duration.ofMillis(200), continuation);
        assertSame(continuation, watchdog.take());
        assertEquals("", stderr());
    }

    @Test
    public void takeRidesOutAnInterruptAndRestoresTheFlag() {
        final LockHoldWatchdog watchdog = new LockHoldWatchdog(fiber, Duration.ofSeconds(10));
        final Runnable continuation = () -> {};
        watchdog.offer(continuation);
        Thread.currentThread().interrupt();
        assertSame(continuation, watchdog.take());
        assertTrue(Thread.interrupted(), "the interrupt flag was restored");
    }

    @Test
    public void aSecondOfferIsABug() {
        final LockHoldWatchdog watchdog = new LockHoldWatchdog(fiber, Duration.ofSeconds(10));
        watchdog.offer(() -> {});
        assertThrows(IllegalStateException.class, () -> watchdog.offer(() -> {}));
    }
}
