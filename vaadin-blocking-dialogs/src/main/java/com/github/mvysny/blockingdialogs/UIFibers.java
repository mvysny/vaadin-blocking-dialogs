/*
 * Copyright 2026 Martin Vysny
 *
 * Licensed under the MIT License. See the LICENSE file in the project root
 * for the full license text.
 */
package com.github.mvysny.blockingdialogs;

import com.github.mvysny.blockingdialogs.uifiber.spi.Completable;
import com.github.mvysny.blockingdialogs.uifiber.spi.UIFiberRunnerSpi;
import com.vaadin.flow.component.AttachEvent;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.DetachEvent;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.internal.CurrentInstance;
import com.vaadin.flow.server.ErrorEvent;
import com.vaadin.flow.server.ErrorHandler;
import com.vaadin.flow.server.VaadinSession;
import com.vaadin.flow.shared.Registration;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Serializable;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.function.Supplier;

/**
 * <i>UI fibers</i>: UI code that may park until a future completes, the session lock released
 * meanwhile, so the browser keeps receiving updates and the answering click gets processed. Start one
 * from a listener; inside it, a wait returns its value on the caller's own stack:
 * <pre>{@code
 * save.addClickListener(e -> UIFibers.runLater(() -> {
 *     CompletableFuture<Report> report = CompletableFuture.supplyAsync(this::buildReport);
 *     show(UIFibers.parkAndAwait(progressBar, report));   // the progress bar anchors the wait
 * }));
 * }</pre>
 * {@link BlockingDialogs} has the dialog helpers on top. A background thread gets into a UI fiber
 * through {@link #access} and {@link #accessSynchronously(UI, Supplier)}.
 * <p>
 * <b>One thread.</b> A UI fiber runs on one thread from start to end, so code after a park sees the
 * thread-locals it saw before - {@link UI#getCurrent()} included, which follows the anchor to a new UI
 * after a {@code @PreserveOnRefresh} reload. {@link com.vaadin.flow.server.VaadinRequest#getCurrent()}
 * and {@link com.vaadin.flow.server.VaadinResponse#getCurrent()} are {@code null} in every UI fiber:
 * capture request-scoped values before starting one.
 * <p>
 * <b>Lifetime.</b> A wait lives as long as its <i>anchor</i>: the anchor's detach ends it, unless the
 * anchor is attached again by the time the current request is handled ({@code @PreserveOnRefresh}).
 * Nothing else kills a parked UI fiber from outside.
 * <p>
 * <b>A dead wait ends quietly, nothing else.</b> A {@link WaitDiedException} escaping a UI fiber ends
 * it quietly; anything else goes to the session's {@link ErrorHandler}, the app's own
 * {@link CancellationException} included. A user-facing Cancel is an answer: the dialog completes its
 * future with a cancel value. So a progress dialog's Cancel must not cancel the future the UI fiber
 * awaits, or the UI fiber fails; await an outcome future, and let Cancel stop the job and complete the
 * outcome.
 * <p>
 * The UI fibers are run by the one {@link UIFiberRunnerSpi} on the classpath.
 * <p>
 * Thread-safe.
 */
public final class UIFibers {
    private static final Logger log = LoggerFactory.getLogger(UIFibers.class);

    /**
     * Set while a UI fiber runs on this thread, cleared while it parks; {@code null} otherwise, never
     * {@code false}.
     */
    private static final ThreadLocal<Boolean> inUIFiber = new ThreadLocal<>();

    private UIFibers() {
    }

    /**
     * Queues {@code body} to run as a new UI fiber, and returns at once. It starts after the calling
     * listener returns - or, called inside a UI fiber, once that UI fiber parks or ends. No other request
     * of the session runs between this call and the UI fiber's first park or end.
     *
     * @throws IllegalStateException unless the calling thread holds the session lock with
     *                               {@link UI#getCurrent()} set - a listener, or code inside
     *                               {@link UI#access}. Background threads call {@link #access}.
     */
    public static void runLater(Runnable body) {
        final UI ui = checkLockedUI();
        final VaadinSession session = ui.getSession();
        final UIFiberRunnerSpi runner = runner();
        final Runnable fiber = asUIFiber(ui, body);
        session.access(() -> runner.runUntilFirstPark(session, fiber));
    }

    /**
     * Runs {@code body} as a new UI fiber, and returns once it parks or ends: the caller's next line
     * sees what the UI fiber did up to its first park. Called inside a UI fiber, runs it inline instead -
     * its parks park the calling UI fiber, and this returns once it ends. No other request of the
     * session runs before this returns.
     *
     * @apiNote The exceptions go to the {@link ErrorHandler} as for {@link #runLater}, never to the
     * caller, inline too. A UI fiber that {@code body} wakes - a dialog's OK click - resumes in the
     * session's next drain of access tasks, not before this returns, as it would from any listener.
     * @throws IllegalStateException as {@link #runLater} does.
     */
    public static void runUntilPark(Runnable body) {
        final UI ui = checkLockedUI();
        if (isInUIFiber()) {
            inline(ui, () -> {
                runReportingErrors(body);
                return null;
            });
        } else {
            runner().runUntilFirstPark(ui.getSession(), asUIFiber(ui, body));
        }
    }

    /**
     * {@link #runLater} for a background thread.
     *
     * @throws com.vaadin.flow.component.UIDetachedException if {@code ui} is detached.
     */
    public static void access(UI ui, Runnable body) {
        Objects.requireNonNull(body);
        runner();  // none or two throw here, not in the ErrorHandler
        // not runUntilPark(): a UI fiber of another session may drain this queue, and would run body inline
        ui.access(() -> runLater(body));
    }

    /**
     * Runs {@code body} and waits until it ends, parks included:
     * <ul>
     *     <li>inside a UI fiber, runs it inline - a park there is nested blocking;</li>
     *     <li>from a background thread not holding the session lock, runs it as a new UI fiber - a job
     *     asking the user mid-way.</li>
     * </ul>
     * Exceptions, {@link WaitDiedException} included, go to the caller, not the
     * {@link ErrorHandler} - as with {@link UI#accessSynchronously}.
     *
     * @return what {@code body} returned.
     * @throws WaitDiedException     if the waiting background thread is interrupted, the interrupt
     *                               flag restored.
     * @throws IllegalStateException from any other thread holding the session lock, which would
     *                               deadlock; or inside a UI fiber, for a {@code ui} of another session.
     */
    public static <T extends @Nullable Object> T accessSynchronously(UI ui, Supplier<T> body) {
        Objects.requireNonNull(body);
        final VaadinSession session = ui.getSession();
        if (isInUIFiber()) {
            if (session != VaadinSession.getCurrent()) {
                throw new IllegalStateException("A UI fiber holds the lock of " + VaadinSession.getCurrent()
                        + ", so it can't wait for a UI fiber of " + ui + " in another session");
            }
            return inline(ui, body);
        }
        if (session != null && session.hasLock()) {
            throw new IllegalStateException(Thread.currentThread() + " holds the session lock outside a UI fiber,"
                    + " so waiting for a UI fiber would deadlock. Use runLater(body) instead");
        }
        final CompletableFuture<T> result = new CompletableFuture<>();
        access(ui, () -> {
            try {
                result.complete(body.get());
            } catch (Throwable t) {
                result.completeExceptionally(t);
            }
        });
        return waitUnwrapped(result::get);
    }

    /**
     * {@link #accessSynchronously(UI, Supplier)} for a UI fiber returning nothing.
     */
    public static void accessSynchronously(UI ui, Runnable body) {
        Objects.requireNonNull(body);
        accessSynchronously(ui, () -> {
            body.run();
            return null;
        });
    }

    /**
     * Parks the calling UI fiber until {@code future} completes, the session lock released, and returns
     * its value on the caller's stack. Afterwards {@link UI#getCurrent()} is the UI the anchor was last
     * attached to.
     *
     * @param anchor whose life this wait belongs to - a dialog anchors its own answer. An anchor
     *               that is never attached never ends the wait; its death cancels {@code future}.
     * @return the value {@code future} completed with. The cause of a failed {@code future} is
     * rethrown as-is, checked or not, and so is the {@link CancellationException} of a cancelled one.
     * @throws WaitDiedException     if the anchor died, or the park is interrupted, the interrupt flag
     *                               restored. Let it escape: the UI fiber ends quietly.
     * @throws IllegalStateException unless called inside a UI fiber.
     */
    public static <T extends @Nullable Object> T parkAndAwait(Component anchor, CompletableFuture<T> future) {
        Objects.requireNonNull(future);
        checkInUIFiber();
        final VaadinSession session = currentSession();
        final Completable<T> wakeUp = runner().newCompletable(session);
        future.whenComplete((value, failure) -> {
            final Runnable wake = failure == null ? () -> wakeUp.complete(value) : () -> wakeUp.fail(unwrap(failure));
            // an already-done future lands here on the UI fiber itself, which must not park for it
            if (session.hasLock()) {
                wake.run();
            } else {
                session.access(wake::run);
            }
        });
        return awaitAnchored(anchor, wakeUp, future);
    }

    /**
     * @return whether the calling thread runs a UI fiber, and so may park.
     */
    public static boolean isInUIFiber() {
        return inUIFiber.get() != null;
    }

    /**
     * Throws unless the calling thread may park: it runs a UI fiber.
     *
     * @throws IllegalStateException if it doesn't.
     */
    public static void checkInUIFiber() {
        if (!isInUIFiber()) {
            throw new IllegalStateException(Thread.currentThread()
                    + " runs no UI fiber, so it can't park. Wrap the code in UIFibers.runLater(() -> ...)");
        }
    }

    /**
     * A new wake-up for the calling UI fiber, for a helper whose listeners hold the lock and so wake it
     * directly: {@link BlockingDialogs#showAndAwait(com.vaadin.flow.component.confirmdialog.ConfirmDialog)}.
     */
    static <T extends @Nullable Object> Completable<T> newCompletable() {
        checkInUIFiber();
        return runner().newCompletable(currentSession());
    }

    /**
     * Parks on {@code wakeUp}, which {@code anchor}'s death fails - {@code future} cancelled with it - and
     * on waking makes current the UI the anchor was last attached to: that follows a
     * {@code @PreserveOnRefresh} migration, and survives a dialog removed once answered.
     *
     * @param future the app's future {@code wakeUp} waits for, if any.
     * @throws WaitDiedException     if the anchor died, or the park is interrupted.
     * @throws CancellationException if {@code wakeUp} fails with one.
     */
    static <T extends @Nullable Object> T awaitAnchored(Component anchor, Completable<T> wakeUp,
                                                         @Nullable CompletableFuture<?> future) {
        final UI ui = Objects.requireNonNull(UI.getCurrent(), "a UI fiber always has a UI");
        final AnchorWatch watch = new AnchorWatch(currentSession(), anchor.getUI().orElse(ui), wakeUp, future);
        final Registration onAttach = anchor.addAttachListener(watch::attached);
        final Registration onDetach = anchor.addDetachListener(watch::detached);
        // a parked UI fiber isn't running: the runner may run other code on this thread meanwhile
        inUIFiber.remove();
        try {
            return waitUnwrapped(wakeUp::park);
        } finally {
            inUIFiber.set(Boolean.TRUE);
            onAttach.remove();
            onDetach.remove();
            if (watch.ui != null) {
                CurrentInstance.setCurrent(watch.ui);
            }
        }
    }

    private static UIFiberRunnerSpi runner() {
        return RunnerLoader.get();
    }

    private static VaadinSession currentSession() {
        return Objects.requireNonNull(VaadinSession.getCurrent(), "a UI fiber always has a session");
    }

    /**
     * @return {@link UI#getCurrent()}, the UI the new UI fiber runs in.
     * @throws IllegalStateException unless the calling thread holds the session lock with
     *                               {@link UI#getCurrent()} set.
     */
    private static UI checkLockedUI() {
        final UI ui = UI.getCurrent();
        final VaadinSession session = ui == null ? null : ui.getSession();
        if (session == null || !session.hasLock()) {
            throw new IllegalStateException("A UI fiber is started holding the session lock with UI.getCurrent() set,"
                    + " which " + Thread.currentThread() + " doesn't have. From a background thread, call"
                    + " UIFibers.access(ui, body)");
        }
        return ui;
    }

    /**
     * {@code body} as the root of a UI fiber, for the runner to run: while it runs,
     * {@link #isInUIFiber()} holds and {@code ui} with its session and service are current. Throws
     * nothing.
     */
    private static Runnable asUIFiber(UI ui, Runnable body) {
        Objects.requireNonNull(body);
        return () -> {
            // the runner's thread may be the caller's own, which gets its instances back
            final Map<Class<?>, CurrentInstance> previous = CurrentInstance.setCurrent(ui);
            inUIFiber.set(Boolean.TRUE);
            try {
                runReportingErrors(body);
            } finally {
                inUIFiber.remove();
                CurrentInstance.restoreInstances(previous);
            }
        };
    }

    /**
     * Runs {@code body} inside the calling UI fiber with {@code ui} current, then brings back the
     * caller's current instances - but keeps the UI a park inside rebound the caller's own UI to.
     */
    private static <T extends @Nullable Object> T inline(UI ui, Supplier<T> body) {
        final UI outer = UI.getCurrent();
        final Map<Class<?>, CurrentInstance> previous = CurrentInstance.setCurrent(ui);
        try {
            return body.get();
        } finally {
            final UI after = UI.getCurrent();
            CurrentInstance.restoreInstances(previous);
            if (ui == outer && after != null && after != ui) {
                CurrentInstance.setCurrent(after);
            }
        }
    }

    /**
     * Runs {@code body}; a {@link WaitDiedException} ends it quietly, anything else goes to the
     * session's {@link ErrorHandler}. Throws nothing, even when the handler does.
     */
    private static void runReportingErrors(Runnable body) {
        try {
            body.run();
        } catch (WaitDiedException e) {
            log.debug("The wait of a UI fiber died, so the UI fiber ended", e);
        } catch (Throwable t) {
            try {
                // the session current now, not the one at the start: a park may have rebound the UI fiber
                final VaadinSession session = VaadinSession.getCurrent();
                final ErrorHandler errorHandler = session == null ? null : session.getErrorHandler();
                if (errorHandler == null) {
                    log.error("A UI fiber failed, and there is no session ErrorHandler to report it to", t);
                } else {
                    errorHandler.error(new ErrorEvent(t));
                }
            } catch (Throwable handlerFailure) {
                handlerFailure.addSuppressed(t);
                log.error("The session ErrorHandler failed on what a UI fiber threw", handlerFailure);
            }
        }
    }

    /**
     * Follows an anchor while a UI fiber waits on it, and ends the wait once the anchor stays
     * detached. The verdict waits for the request that detached it: in a {@code @PreserveOnRefresh}
     * navigation the view is detached from the old UI, the access queue drains inside
     * {@code prevUi.close()}, and only then is the view attached to the new UI. So a check queued on
     * detach that finds the anchor detached looks once more just before the response of the UI that
     * detached it; with that UI closing - a session destroy, a tab close - it ends the wait at once.
     * <p>
     * Transient fields: a parked UI fiber never survives session serialization, and neither the
     * wake-up nor a UI belong in the serialized component tree.
     */
    private static final class AnchorWatch implements Serializable {
        /**
         * Captured up front: on session destroy a UI loses its session before its tree detaches.
         */
        @Nullable
        private final transient VaadinSession session;
        /**
         * The UI the anchor was last attached to.
         */
        @Nullable
        private transient UI ui;
        @Nullable
        private final transient Completable<?> wakeUp;
        @Nullable
        private final transient CompletableFuture<?> future;

        AnchorWatch(VaadinSession session, UI ui, Completable<?> wakeUp, @Nullable CompletableFuture<?> future) {
            this.session = session;
            this.ui = ui;
            this.wakeUp = wakeUp;
            this.future = future;
        }

        void attached(AttachEvent event) {
            ui = event.getUI();
        }

        void detached(DetachEvent event) {
            if (session == null || wakeUp == null) {
                return; // deserialized: the wait is gone already
            }
            final Component anchor = event.getSource();
            final UI detachingUI = UI.getCurrent();
            session.access(() -> {
                if (anchor.isAttached()) {
                    return;
                }
                if (detachingUI == null || detachingUI.isClosing() || detachingUI.getSession() == null) {
                    end();
                } else {
                    detachingUI.beforeClientResponse(detachingUI, context -> {
                        if (!anchor.isAttached()) {
                            end();
                        }
                    });
                }
            });
        }

        /**
         * Called holding the lock. Cancels the app's future too: a background job sees the wait is gone.
         */
        private void end() {
            Objects.requireNonNull(wakeUp).fail(new WaitDiedException("The anchor of the wait detached", null));
            if (future != null) {
                future.cancel(false);
            }
        }
    }

    /**
     * A wait, {@link Completable#park()} or {@link CompletableFuture#get()}.
     */
    @FunctionalInterface
    private interface Wait<T extends @Nullable Object> {
        T get() throws ExecutionException, InterruptedException;
    }

    /**
     * {@code wait} with the {@link #parkAndAwait} exception contract.
     *
     * @implNote Unwraps a {@link CancellationException}: JDK 23+ {@link CompletableFuture#get()}
     * throws a new one, the one it was completed with attached - a body's {@link WaitDiedException}
     * would reach {@link #accessSynchronously}'s caller as a plain one.
     */
    private static <T extends @Nullable Object> T waitUnwrapped(Wait<T> wait) {
        try {
            return wait.get();
        } catch (CancellationException e) {
            throw e.getCause() instanceof CancellationException cause ? cause : e;
        } catch (ExecutionException e) {
            throw sneakyThrow(e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new WaitDiedException("The wait was interrupted", e);
        }
    }

    /**
     * The cause of a dependent stage's failure: {@code supplyAsync()} wraps what it threw.
     */
    private static Throwable unwrap(Throwable failure) {
        return failure instanceof CompletionException && failure.getCause() != null ? failure.getCause() : failure;
    }

    /**
     * Throws {@code t} as-is, checked or not; declared to return so that callers can {@code throw} it.
     */
    @SuppressWarnings("unchecked")
    private static <E extends Throwable> RuntimeException sneakyThrow(Throwable t) throws E {
        throw (E) t;
    }
}
