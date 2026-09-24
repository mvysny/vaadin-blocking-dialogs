# Blocking strategy: manual session unlock instead of virtual threads

Ported from SB-Emulators' `ideas/blocking-strategy-manual-session-unlock.md` (probed on Vaadin
25.2.6 / JDK 25, 2026-09-01). There it was a knob inside one app; here it becomes the
`vaadin-blocking-dialogs-session-unlock` module, a second implementation of the common API
(`D_pluggable_strategy`; the API is `BlockingExecutor`, the shared half `StrategySupport`). The measured Vaadin behaviour has
already moved to `design/research.md` — `R_unlock_pushes`, `R_async_push_no_response`; this file
keeps only the design, which is not done yet.

Graduates when the module lands: the founding reasoning goes to a `D_`, the flow to
`design/architecture.md`, the rest to doc comments.

## Whose idea this is

**Matthias Perktold's.** He proposed it and demonstrated it in
[github.com/mperktold/blocking-dialogs](https://github.com/mperktold/blocking-dialogs/), which works
through four approaches and lands on two viable ones — *Release Lock* and *Await Lock*, both below.
Martin's writeup is [Blocking Dialogs in Vaadin: Solution Details](https://mvysny.github.io/vaadin-blocking-dialogs/)
(2023-01-12). The README credits him; keep it that way.

## The mechanism

The flush to the browser is caused by the session lock reaching hold count 0, not by the request
completing (`R_unlock_pushes`). So an ordinary *platform* thread can release every hold, park on
the answer, and re-take the holds — Matthias's *Release Lock*:

```java
int holds = ((ReentrantLock) session.getLockInstance()).getHoldCount();
for (int i = 0; i < holds; i++) session.unlock();   // the last one flushes + pushes the dialog
try {
    return future.get();                            // park the platform thread
} finally {
    for (int i = 0; i < holds; i++) session.lock(); // re-acquire, continue on the same stack
}
```

**It must not be the request thread.** Parked there, the push arrives but is stamped async, so the
client never ends the request: the spinner spins forever and the answering click is postponed
until the park gives up (`R_async_push_no_response`). So `run(block)` hands the block to a worker
platform thread and returns; the request responds normally; the worker takes the session lock,
sets the `CurrentInstance`s, runs the block, and does the unlock/park/relock dance around each
await. That shape is measured and works.

**Keeping one Java stack is load-bearing.** The obvious alternative — two `ui.access(...)` lambdas
around the block — cannot work, because `if (confirm(…))` must return into the middle of user code.
Holding the lock across the whole block and dropping it only around the park is what preserves
that stack.

**The framing that makes this a small hack**, and the line worth carrying into the eventual `D_`:
the worker is the ordinary Vaadin "background thread updates the UI under the session lock"
pattern. The one unusual move is holding that lock across the *whole* block rather than one short
burst — a supported-API shape (`VaadinSession.lock()` / `getLockInstance()` are public), where loom
needs a package-private reflection hack plus `--add-opens`.

### *Await Lock* — Matthias's second approach, not yet probed

Instead of counting holds, `condition.await()` on a `Condition` from the session's `ReentrantLock`:
AQS releases the whole hold count atomically and restores it on wake, so the bookkeeping of
`Q_reentrancy` disappears. The catch: `await()` bypasses `VaadinSession.unlock()`, so nothing is
flushed; an explicit `ui.push()` right before `await()` should fix it (`R_unlock_pushes`, the
unverified bullet). Probe that before choosing between the two.

## What it buys over loom

- **No JDK floor of its own** — a platform thread cannot pin (`R_vt_pinning`); the floor is Vaadin's.
- **No reflection, no `--add-opens`** (`R_vt_scheduler`).
- **No `VirtualThreadAwareLock` routing** — a platform thread that called `session.lock()` genuinely
  holds it (`R_vt_lock_identity`).
- **Request threads may be virtual again** — no `useVirtualThreadsIfAvailable(false)`.

`@Push` stays mandatory either way.

## Open questions, roughly in the order they bite

Three are already flagged in Matthias's README — browser closed on an open dialog
(`Q_cancellation`), nested blocking and deadlock risk (`Q_reentrancy`), "blocking without virtual
threads is a waste of resources" (`Q_scale_budget`). His "you can either block or make changes to
the UI, but never both" is `Q_modal_gap` from the other end. **Start the design pass from his list.**

- **`Q_karibu_determinism`** — the sharpest one. Loom is testable under Karibu *because* Karibu runs
  `UI.access` tasks on the test thread when it drains the queue. A real worker reintroduces a race
  between `_click` and the assertion, and Karibu's thread-locals are per-thread. Options: an
  `INLINE` executor for tests (then the suite doesn't exercise the real strategy — the classic
  hazard), or a test hook that waits until the worker is parked or finished before each lookup
  (Karibu's `TestingLifecycleHook.awaitBeforeLookup` looks like the seam).
- **`Q_modal_gap`** — now a requirement: `runLater` promises input exclusion until the block's
  first park or end (`D_input_exclusion`). The handoff opens a window
  loom does not have — the request responds *before* the worker has the lock (3 ms in the probe; a
  non-fair lock queue under load), so a fast second click is processed first. Candidate fix, to
  probe: the listener's request thread releases its holds, waits for the worker's first park or end,
  re-takes them and responds; the second click queues behind the worker. Rejected in advance: a UI
  curtain at handoff — new machinery that only patches the window instead of closing it.
  `runUntilPark` is the same wait at the call site instead of before the response
  (`D_run_until_park`): release, wait for the first park or end, re-take, return.
- **`Q_cancellation`** — mostly answered by the anchor model (`D_anchored_wait`): a parked worker is released because its future is cancelled when its anchor dies,
  and it unwinds through `CancellationException`; session destroy and tab close both reach it
  (`R_session_destroy_detaches`). Left for this strategy: a closed `@PreserveOnRefresh` tab is only noticed at
  heartbeat expiry (default ~15 min), and its worker is held until then — price it in `Q_scale_budget`.
- **`Q_stale_after_relock`** — while unlocked, other requests mutate the UI freely; the UI may
  detach, the session may invalidate. On re-lock, rebind the `CurrentInstance`s to
  the anchor's last UI, as `StrategySupport.awaitAnchored` already does — shared with loom, not new risk.
- **`Q_reentrancy`** — nested dialogs, and a block started from inside a block. Hold-count
  bookkeeping must survive N levels; *Await Lock* dissolves it. The probe only did one level.
- **`Q_worker_pool`** — who owns the pool: one per session, per UI, or app-wide? Bounded? What
  happens when it is exhausted — reject, queue, or run inline and deadlock-loudly? Name the threads so
  a thread dump says which UI a parked worker belongs to.
- **`Q_lock_fairness`** — the session lock is a non-fair `ReentrantLock`, so a re-locking worker can
  be barged by a stream of requests. Probably academic at LOB concurrency; note it, don't design for it.
- **`Q_scale_budget`** — one parked platform thread per open dialog per user, held for human
  think-time. Pick and state the number we accept in the README, rather than discovering it.

## Re-measuring

The probe was a throwaway `@Route("probe")` in a Vaadin Boot app with `@Push`, dev mode, driven
through a real Firefox via Playwright: button **A** parks the request thread, **B** hands off to a
worker platform thread and parks that, **C** is a non-blocking control; `?transport=` flipped
`ui.getPushConfiguration().setTransport(...)`. Recreate it in the testapp's session-unlock route
if the measurement needs redoing — and probe *Await Lock* there.
