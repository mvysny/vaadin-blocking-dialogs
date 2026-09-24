# Research — the stack: Vaadin Flow's session lock and push, and the JDK's virtual threads

What the things we don't own actually do. About *them*, never us: a sentence starting "we chose"
is a `D_`. `## R_<slug> — <title>`, one claim per bullet, one provenance marker per claim —
**[docs]**, **[src]**, **[verified <date>, <version>]**, **[unverified]** (a hypothesis; a design
built on it says so). A claim is earned by its provenance, or by having cost real work to find
out. Checked against Vaadin 25.2 and JDK 21 / 24 / 25; a version-sensitive claim names the version
it was seen on. Cite by slug, `R_<slug>`, never by position; `grep '^## R_' design/research.md` is
the index. The first entry is the ruler: every later one trims to its length — which is how long
this file gets, so keep it short. When you have written an entry, re-read it against the one
above and cut the fat — a marker already says where a claim came from.

---

## R_unlock_pushes — `VaadinSession.unlock()`: the ultimate release flushes and pushes

- UI changes reach the browser when the session lock drops to hold count 0, not when the request
  completes: the ultimate `VaadinSession.unlock()` runs the pending `UI.access()` tasks, then calls
  `ui.push()` on every UI whose push mode is `AUTOMATIC`. **[src, Vaadin 25.2.6]**
- A platform thread that calls `unlock()` `getHoldCount()` times in the middle of a listener gets
  its pending changes, an open dialog included, pushed to the browser. **[verified 2026-09-01,
  Vaadin 25.2.6, JDK 25]**
- `Condition.await()` on the session's `ReentrantLock` releases the whole hold count through AQS,
  never running `VaadinSession.unlock()` — so it drains nothing and pushes nothing. **[src]**
- Calling `ui.push()` just before `await()` makes up for it (the lock is still held there, and
  `push()` drains the access queue itself). **[unverified]**
- Without `@Push` nothing is pushed; the changes wait for the client's next request. **[src]**
- The pending tasks and the push both run *before* `getLockInstance().unlock()` really releases
  the lock, so a request queued on the lock sees their effects — an `access` task that opens a modal
  dialog has it open before the next request is handled. **[src, Vaadin 25.3.0]**

## R_async_push_no_response — Flow's client: an async push never ends a request

- The client keeps one request outstanding: `MessageSender.sendInvocationsToServer` postpones
  while `RequestResponseTracker.hasActiveRequest()`. **[src, Vaadin 25.2.6]**
- Only `MessageHandler.endRequestIfResponse` clears it, and only for a message without
  `meta.async`; `ui.push()` always sends `createUidl(ui, async=true)`. `stopLoading()` sits in the
  same branch. **[src]**
- Parking the *request* thread after a manual unlock: the dialog renders, but the loading indicator
  spins forever and the click waits in the browser ("Postpone sending invocations to server because
  of active request") until the parked request responds. Same on `WEBSOCKET` and `WEBSOCKET_XHR`.
  **[verified 2026-09-01, Vaadin 25.2.6, Firefox]**
- Handing the listener's code to a worker platform thread works: the request responds normally,
  the worker locks, opens the dialog, unlocks and parks; the click arrives as an ordinary request,
  no spinner, and the worker resumes on its own stack. **[verified 2026-09-01, Vaadin 25.2.6]**
- Pushing with `async=false` to fake a response breaks later: the parked request's real response
  still arrives, and the client's `endRequest()` with no request active throws
  `IllegalStateException`. **[src]**

## R_vt_pinning — JDK 21-23: a virtual thread parked inside a monitor pins its carrier

- Before JEP 491 (JDK 24) a virtual thread that parks while holding a monitor cannot unmount; it
  falls through to `parkOnCarrierThread()` and blocks its carrier. **[docs]**
- With the carrier inside `UI.access()` holding the session lock, that is a deterministic deadlock:
  no response, no dialog, the session stuck for good. Any monitor above the park triggers it,
  JDK-internal ones included (`AbstractPreferences.put()` is `synchronized`). **[verified, Temurin
  21.0.11 hangs; Temurin 24.0.2 and OpenJDK 25.0.4 work — vaadin-loom#2]**
- `-Djdk.tracePinnedThreads=full` prints the pinning stack on 21-23; JDK 24 removed the flag.
  **[docs]**

## R_vt_scheduler — running virtual threads on a scheduler of our own

- No public API sets a virtual thread's scheduler (JDK-8308541); the package-private
  `java.lang.ThreadBuilders$VirtualThreadBuilder(Executor)` constructor does, through
  `setAccessible` and `--add-opens java.base/java.lang=ALL-UNNAMED`. **[src]**
- The hack works on Oracle OpenJDK, Corretto and Temurin 21 / 24 / 25. **[verified, vaadin-loom's CI
  matrix]**
- A continuation cannot run on a virtual carrier: `WrongThreadException` at
  `VirtualThread.runContinuation`. **[verified, vaadin-loom]**
- `Thread.ofVirtual()` inside such a thread inherits its scheduler; `Thread.ofPlatform()` and
  `new Thread()` don't. **[verified, JDK 25, vaadin-loom]**
- `ExecutorService.shutdownNow()` interrupts parked virtual threads, and the interrupt unparks them
  through the scheduler — which is called during close, and from `jcmd Thread.dump_to_file`.
  **[verified, vaadin-loom#1]**

## R_vt_lock_identity — `ReentrantLock` on a virtual thread whose carrier holds it

- `ReentrantLock` keys ownership on `Thread` identity, so a virtual thread mounted inside its
  carrier's `UI.access()` sees `isHeldByCurrentThread() == false`. **[src]**
- Taking that lock from the virtual thread recurses until `StackOverflowError`: every release
  unparks it, which resubmits its continuation through `ui.access()`, whose `unlock()` releases
  again. **[verified, vaadin-loom#3]**
- `hasQueuedThreads()` and friends are `final`, so a delegating wrapper reports an empty queue;
  `VaadinService.isUIActive()` loses the grace period it grants a UI whose lock has waiters. **[src]**
- The session lock is stored under the attribute `getServiceName() + ".lock"`
  (`getLockAttributeName()` is private), created under `synchronized (VaadinService.class)`, and
  `VaadinSession.refreshLock()` asserts the instance never changes. **[src]**

## R_preserve_migration — F5 on a `@PreserveOnRefresh` route: dialogs move at once, the view later

- `AbstractNavigationStateRenderer.disconnectElements` removes the preserved chain's root from the
  old UI, moves the old UI's other children to the new one (`moveElementsFrom`), then calls
  `prevUi.close()`; the chain re-attaches later in that navigation. **[src, Vaadin 25.3.0]**
- Each moved child (dialog, notification) is detached, then appended to the new UI in the same
  call; its attach listeners see `isInitialAttach() == true`. **[src, Vaadin 25.3.0]**
- With a push connection, `UI.close()` runs the pending access tasks: a `session.access` queued on
  detach runs inside `disconnectElements`, and sees a moved dialog attached, the chain detached.
  **[verified 2026-09-24, Vaadin 25.3.0, Karibu 2.7.3]**
- When the window name is not known yet, the navigation first fetches it in a round trip; until
  then the chain stays on the old UI. **[src, Vaadin 25.3.0]**
- The unload beacon does not close the old UI of a preserved view. **[unverified — read for
  SB-Emulators, not re-read here]**
- Karibu 2.7.1+ reproduces this order in `MockPage.reload()` (karibu-testing#207). **[docs]**

## R_session_destroy_detaches — session destroy detaches every UI's tree

- `VaadinService.fireSessionDestroy` runs as a `session.access` task: per UI `ui.close()`, then
  `session.removeUI(ui)` → `UIInternals.setSession(null)` → the UI root node's `setParent(null)`, so
  detach listeners fire for the whole tree; then the destroy listeners. **[src, Vaadin 25.3.0]**
- A `session.access` queued from such a detach listener runs in the same pass:
  `runPendingAccessTasks` polls the queue until it is empty. **[src, Vaadin 25.3.0]**
- A closed tab reaches `removeUI` too, via `removeClosedUIs` — at once on the unload beacon,
  otherwise after the missed-heartbeat timeout (3 × the 5-minute default interval). **[docs]**
