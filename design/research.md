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
- A push's UIDL runs the pending `ui.beforeClientResponse(...)` executions, just as a response's
  does: `AtmospherePushConnection` and the request both go through `UidlWriter.createUidl`, whose
  `encodeChanges` calls `StateTree.runExecutionsBeforeClientResponse()` first. **[src, Vaadin 25.3.0]**
- `session.access()` from a thread that finds the lock free takes it and releases it at once
  (`ensureAccessQueuePurged`), so that thread drains the whole queue — a background thread can end
  up running other threads' access tasks. **[src, Vaadin 25.3.0]**

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
- `start()` on a platform thread hands the first continuation to the scheduler synchronously
  (`submitRunContinuation` → `scheduler.execute`), and nothing after it assumes it hasn't run: the
  scheduler may run it right there, and `start()` returns at its first unmount or end. **[src, JBR
  25.0.4; verified, `LoomUIFiberRunnerTest`]**
- The same from a virtual thread: `start()` calls `scheduler.execute` on the caller. So does an
  unpark, on the unparking thread. **[verified, JBR 25.0.4, `VirtualDrainerTest`]**
- `Thread.ofVirtual()` inside such a thread inherits its scheduler; `Thread.ofPlatform()` and
  `new Thread()` don't. **[verified, JDK 25, vaadin-loom]**
- `ExecutorService.shutdownNow()` interrupts parked virtual threads, and the interrupt unparks them
  through the scheduler — which is called during close, and from `jcmd Thread.dump_to_file`.
  **[verified, vaadin-loom#1]**

## R_vt_lock_identity — `ReentrantLock` on a virtual thread whose carrier holds it

- `ReentrantLock` keys ownership on `Thread` identity, so a virtual thread mounted inside its
  carrier's `UI.access()` sees `isHeldByCurrentThread() == false`. **[src]**
- The virtual thread can never take that lock itself. With a carrier that ends its access task at
  every unmount, trying recurses until `StackOverflowError`: every release unparks it, which
  resubmits its continuation through `ui.access()`, whose `unlock()` releases again. **[verified,
  vaadin-loom#3]**
- With a carrier that waits out the unmount holding the lock, trying deadlocks the session.
  **[verified 2026-09-25, JBR 25.0.4 — `VirtualThreadAwareLockTest`]**
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
- The unload beacon does not close the old UI of a preserved view, so a closed tab of a preserved
  route lingers until the missed-heartbeat timeout (`R_session_destroy_detaches`), a UI fiber
  parked in it too. **[unverified in a browser; Karibu 2.7.3's `MockBrowser.closeTab` models it]**
- Karibu 2.7.1+ reproduces this order in `MockPage.reload()` (karibu-testing#207). **[docs]**

## R_session_destroy_detaches — session destroy detaches every UI's tree

- `VaadinService.fireSessionDestroy` runs as a `session.access` task: per UI `ui.close()`, then
  `session.removeUI(ui)` → `UIInternals.setSession(null)` → the UI root node's `setParent(null)`, so
  detach listeners fire for the whole tree; then the destroy listeners. **[src, Vaadin 25.3.0]**
- A `session.access` queued from such a detach listener runs in the same pass:
  `runPendingAccessTasks` polls the queue until it is empty. **[src, Vaadin 25.3.0]**
- A closed tab reaches `removeUI` too, via `removeClosedUIs` — at once on the unload beacon,
  otherwise after the missed-heartbeat timeout (3 × the 5-minute default interval). **[docs]**
- `removeClosedUIs` removes each closed UI inside its own `ui.accessSynchronously`, so detach
  listeners see the closing UI as `UI.getCurrent()`, whichever tab's request reaps it. **[src, Vaadin
  25.3.0]**

## R_vt_unmount_ends_segment — loom: every unmount returns a virtual thread's continuation to its carrier, not only a park

- `VirtualThread.runContinuation` returns at *any* unmount — a socket read (a JDBC query),
  `Thread.sleep`, `Thread.yield`, a park — with `afterYield` / `afterDone` already run, so the
  thread's state is settled by then. **[src, JDK 21 and 25]**
- So a carrier that runs it as a `session.access` task and returns with it drops the session lock
  mid-IO: another request ran between two statements, a second thread took the lock mid-read, and
  a double click got in before the first dialog. **[verified 2026-09-24, Vaadin 25.3.0, Karibu
  2.7.3, JBR 25.0.4 — the probe that became `IoUnmountTest`]**
- The wake-up comes through the same scheduler, from the waking thread — or from the carrier
  itself, inside the returning `runContinuation`, when an unpark beats the park (`afterYield`) and
  on every `Thread.yield()`. **[src, JDK 25]**
- `Thread.getStackTrace()` of an unmounted virtual thread on a scheduler of our own returns its
  suspended stack. **[verified 2026-09-25, JBR 25.0.4 — `LockHoldWatchdogTest`]**
- `jstack` / `jcmd Thread.print` list platform threads only; virtual threads take
  `jcmd <pid> Thread.dump_to_file`. **[docs, JEP 444]**
- A contended `ReentrantLock`, `BlockingQueue.take()` and `synchronized` on JDK 24+ unmount too;
  file IO does not (the JDK pins the carrier for it). **[unverified]**

## R_service_init_listeners — `VaadinServiceInitListener`: ServiceLoader finds it under Spring too; a session listener's throw is only logged

- `DefaultInstantiator.getServiceInitListeners()` is `ServiceLoader.load(VaadinServiceInitListener
  .class, service.getClassLoader())`. **[src, Vaadin 25.2.6; flow 2.4 alike]**
- `SpringInstantiator` returns those first, then every `VaadinServiceInitListener` bean, then a
  bridge that publishes `ServiceInitEvent` to `@EventListener`s. **[src, vaadin-spring 25.2.7; the
  same concat since 12.0, Vaadin 14]**
- A class both in `META-INF/services` and a `@Component` is two instances, both getting
  `serviceInit()`; the ServiceLoader one isn't Spring-managed, its `@Autowired` fields `null`. No
  code path drops a session listener because the file exists. **[src, flow 2.4 and 25.2.6]**
- In a Spring Boot fat jar the service's classloader sees `BOOT-INF/lib`, so a library jar's
  `META-INF/services` is found. **[unverified]**
- A `SessionInitListener` that throws doesn't fail the session: the exception goes to
  `session.getErrorHandler()` (`DefaultErrorHandler` logs it), and the session and the remaining
  listeners carry on. **[src, Vaadin 25.2.6 `onVaadinSessionStarted`; 25.3.0 through
  `VaadinServiceEventBus.fireEvent(event, sessionErrorHandler(session))`]**
- An `Error` gets past both that catch and `handleRequest`'s, so the request fails. The session is
  stored before the listeners run, so the next request of that HTTP session finds it and skips
  them. **[src, Vaadin 25.3.0]**
- Karibu's `MockVaadin.setup()` fires the session init listeners itself, without that catch, so
  there the throw fails `setup()`. **[src, Karibu 2.7.3]**
