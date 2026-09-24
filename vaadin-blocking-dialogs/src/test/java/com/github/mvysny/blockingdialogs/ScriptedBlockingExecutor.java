/*
 * Copyright 2026 Martin Vysny
 *
 * Licensed under the MIT License. See the LICENSE file in the project root
 * for the full license text.
 */
package com.github.mvysny.blockingdialogs;

import com.github.mvysny.kaributesting.v10.MockVaadin;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.internal.CurrentInstance;
import com.vaadin.flow.server.VaadinSession;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.locks.ReentrantLock;

/**
 * A strategy that tests the strategy-neutral half on Karibu's test thread, which holds the session
 * lock throughout. A block starts from {@link UI#access}, as under loom; a park doesn't park, it plays
 * the browser instead:
 * <pre>{@code
 * ScriptedBlockingExecutor.user.add(() -> _fireConfirm(_get(ConfirmDialog.class)));  // the user's answer
 * BlockingDialogs.runLater(() -> outcome = BlockingDialogs.showAndAwait(dialog));
 * ScriptedBlockingExecutor.endRequest();                                             // the block runs
 * }</pre>
 * A park ends the current request, runs the next scripted user action as a new request, and ends that
 * too; by
 * then the future must be done. Karibu's queue runs {@code ui.access()} tasks, blocks included, from the
 * nearest roundtrip, so a block queued inside a block starts at the park, as in production. So everything real strategies share is exercised, the park itself not.
 */
public final class ScriptedBlockingExecutor implements BlockingExecutor {
    /**
     * What the user does at each park, in order - usually a click on a dialog button.
     */
    @NotNull
    public static final Deque<Runnable> user = new ArrayDeque<>();

    /**
     * The current instances of Karibu's mocked request, which the user acts in; an access task, and
     * so a block, runs without them. Follows the user to a new UI after a reload.
     */
    @Nullable
    private static Map<Class<?>, CurrentInstance> request;

    /**
     * Call right after {@code MockVaadin.setup()}, on the test thread.
     */
    public static void setup() {
        request = CurrentInstance.getInstances();
    }

    /**
     * Call before {@code MockVaadin.tearDown()}; fails on scripted user actions no block parked for.
     */
    public static void tearDown() {
        final List<Runnable> unused = List.copyOf(user);
        user.clear();
        request = null;
        if (!unused.isEmpty()) {
            throw new AssertionError(unused.size() + " scripted user actions, but no block parked for them");
        }
    }

    /**
     * Ends the current request: the blocks queued by {@code runLater()} start, park and play their
     * scripted user actions. Afterwards the test thread is on the UI the user ended on, which a reload
     * inside a block changes - and which Vaadin's access-queue drain would otherwise reset.
     */
    public static void endRequest() {
        MockVaadin.runUIQueue();
        CurrentInstance.restoreInstances(Objects.requireNonNull(request, "call setup() first"));
        MockVaadin.clientRoundtrip();
    }

    @Override
    public void runLater(@NotNull Runnable block) {
        final UI ui = StrategySupport.checkLockedUI();
        ui.access(() -> StrategySupport.runBlock(ui, block));
    }

    /**
     * Runs the block right here, inside a block or not: a scripted park doesn't park, so the whole
     * block runs before this returns, its scripted user actions included.
     */
    @Override
    public void runUntilPark(@NotNull Runnable block) {
        StrategySupport.runBlock(StrategySupport.checkLockedUI(), block);
    }

    @Override
    public <T> T parkAndAwait(@NotNull Component anchor, @NotNull CompletableFuture<T> future) {
        checkUIThreadWithBlockingCapabilities();
        return StrategySupport.awaitAnchored(anchor, future, () -> {
            // Karibu's roundtrip needs the one hold the test thread started with; the rest are the
            // block's, from ui.access() - released around the park as session-unlock will do
            final VaadinSession session = VaadinSession.getCurrent();
            final int blockHolds = ((ReentrantLock) session.getLockInstance()).getHoldCount() - 1;
            for (int i = 0; i < blockHolds; i++) {
                session.unlock();
            }
            try {
                MockVaadin.clientRoundtrip();
                final Runnable action = user.poll();
                if (action == null) {
                    throw new AssertionError("A block parked, but the test scripted no user action for it");
                }
                final Map<Class<?>, CurrentInstance> block = CurrentInstance.getInstances();
                CurrentInstance.restoreInstances(Objects.requireNonNull(request, "call setup() first"));
                try {
                    action.run();
                    MockVaadin.clientRoundtrip();
                    request = CurrentInstance.getInstances();
                } finally {
                    CurrentInstance.clearAll();
                    CurrentInstance.restoreInstances(block);
                }
            } finally {
                for (int i = 0; i < blockHolds; i++) {
                    session.lock();
                }
            }
            if (Thread.interrupted()) {
                throw new InterruptedException();
            }
            if (!future.isDone()) {
                throw new AssertionError("The user action left the future pending: a real strategy would park forever");
            }
            return future.get();
        });
    }
}
