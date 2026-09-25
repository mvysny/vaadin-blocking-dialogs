# Decisions

Why this project is the way it is and not otherwise — FAQ-shaped: each entry is a question and
its current answer. Rewrite the answer when it changes; delete the entry when nobody asks any
more. An entry is earned by what it would cost to reverse — half the code base — or by research
the next person would otherwise redo (cited as its `R_`). Not an entry: windows → panels
"because that's the trend", this red over that red, `get_foo` over `is_foo?`, the testing library,
the CI host, a version bump — a comment at the site of the choice, or nothing; nothing about
`design/` itself. Cite by slug, `D_<slug>`, never by position; `grep '^## D_' design/decisions.md`
is the index. The first entry is the ruler: every later one trims to its length — which is how
long this file gets, so keep it short. When you have written an entry, re-read it against the one
above, open the doc comments it touches and cut what they already say, then cut the fat.

---

## D_pluggable_runner — Why one API over pluggable UI fiber runners rather than one blocking mechanism?

Blocking a Vaadin listener takes two things at once: the code parks until the user answers, and
the session lock is released so the dialog reaches the browser and the answering click can be
processed. Two mechanisms do that, and each pays what the other doesn't. Virtual threads park
without holding a thread, but need JDK 24+ (`R_vt_pinning`), reflection into the JDK plus
`--add-opens` (`R_vt_scheduler`), a session-lock wrapper (`R_vt_lock_identity`) and platform request
threads. A platform thread parked with the session lock released needs none of that, but holds one
thread per open dialog and must run on a worker so the request can respond
(`R_async_push_no_response`). So app code sees only `vaadin-blocking-dialogs`, and the runner is
picked where the app is wired (**One API, any runner**). Why not loom only: its JDK floor and its
reach into JDK internals are what a conservative line-of-business app cannot take. Why not
session-unlock only: it is one probe old, and gives up virtual threads' scale. The cost we carry:
the API is only what every runner can implement — the SPI's two calls (`D_spi_split`) — and a UI
fiber may not lean on what one runner gives for free, such as a bare `future.get()` that loom
tolerates.

## D_anchored_wait — Why does a wait end when its anchor detaches, rather than with an executor scope per UI, session or tab?

A wait belongs to a component — a dialog anchors its own answer — and that component's detach is
the one signal every death of a wait sends: navigating away, closing the dialog, a session destroy
and a tab close all detach it (`R_session_destroy_detaches`). A scope — a registry of parked UI fibers
per UI or session, killed from outside — would duplicate that signal as state both runners keep
in step, so there is none, and no backstop either. The verdict waits for the detaching UI's next
response, because F5 on a `@PreserveOnRefresh` route detaches the view before it re-attaches it
(`R_preserve_migration`). Why not a `VaadinRequestInterceptor.requestEnd` hook for that verdict: it
needs a `VaadinServiceInitListener` shipped in our jar, and still a fallback for a destroy outside a
request. Why no anchorless `parkAndAwait`: a wait that names no owner can only leak. Why no
interrupt at session destroy for a bare park: loom holds the lock through it
(`D_loom_holds_the_lock`), so the destroy never runs, and the lock-hold watchdog has already named
the line. The cost: a
closed `@PreserveOnRefresh` tab is noticed only at heartbeat expiry — free under loom, a held worker
thread under session-unlock.

## D_spi_exactly_one — Why is the runner found through `ServiceLoader`, exactly one, rather than handed in by the app?

A setter, or a runner the app builds, is global mutable state that apps take up as a wiring
API — and then "which runner runs this UI fiber?" has more than one answer, and every call must route
by runner. With exactly one on the classpath the runner is a dependency choice (**One API, any
runner**), `UIFibers` is statics over it, and none or two is an error on every call. A runner that
needs configuration reads it itself, as SPI providers do. The API's own tests run on loom, a test
dependency. Why not a scripted test runner that plays the user's click inside the park: it never
exercises the real runner, parks included; its one gift, no race between a click and an assertion,
only the background-thread runner needs. The cost: one classpath runs one runner, so demoing both
takes one app per runner.

## D_spi_split — Why does a runner implement an SPI of its own, rather than the interface apps call?

An interface both implemented and called invites every runner to redo the runner-neutral half — the
`CurrentInstance`s, the in-fiber flag (`D_in_fiber_flag`), the `ErrorHandler` routing, the anchor
watch, the inline branch — and to forget a piece of it. So `vaadin-uifiber-spi` holds only what a
runner must do, `runUntilFirstPark` and the `Completable` a fiber parks on (`D_runner_owns_park`);
`vaadin-blocking-dialogs` wraps every SPI call in that half, and a runner depends on the SPI alone.
No `runLater` in the SPI: the access tasks pending at the ultimate unlock run before the lock is
really released (`R_unlock_pushes`), so `runLater` is `session.access(() -> runUntilFirstPark(…))`
on every runner. The name: it runs UI fibers, parking included; `Spi`, as the JCA's `SignatureSpi`
beside `Signature`, because the API depends on it and so it sits in every app's autocomplete. Why not
`…Executor`: `Executor.execute` runs any task, some time, on any thread, and invites
`CompletableFuture.runAsync(…, it)` — both break the lock contract. The cost: a runner's setup
error inside `runLater`'s drain lands in the `ErrorHandler`, not at the call, so a runner checks
its setup at its own earliest point, as loom's `SessionLockCheck` does at session init.

## D_input_exclusion — Why does `runLater` keep the session's other requests out until the UI fiber's first park, rather than just queue the UI fiber?

The double-clicked Save button: the second click must find the first click's dialog already open,
so that Vaadin's server-side modality drops it — Swing's "a modal blocks input from
`setVisible(true)`". Without it every blocking action runs twice on a fast double click. Loom gives it by construction: the UI fiber's
first segment runs inside the click request's ultimate unlock, before the lock is really released
(`R_unlock_pushes`). A runner whose UI fiber starts after the request responds pays for it: the
request waits for the UI fiber's first park or end before responding — short and bounded, unlike the
endless park of `R_async_push_no_response`. Not promised: a first segment that ends without parking
releases the lock like any listener, and a later click runs the listener again.

## D_run_until_park — Why `runUntilPark` beside `runLater`, rather than one entry point that returns after the first park?

Code after the call sometimes must see what the UI fiber did. SB-Emulators runs every Swing listener
as a UI fiber, follows it with an epilogue that reconciles state, and its tests read that state
without a Karibu lookup — the only thing draining the access queue after `_click`. Draining it at
the call site works under loom by accident and not under session-unlock (**One API, any
runner**), whose request owes the first-park wait of `D_input_exclusion` anyway. Why not make
`runLater` wait: inside a UI fiber it can't — the new UI fiber needs the lock its caller holds — so
`runUntilPark` runs it inline, parks included. A different promise, a different name, as
`UI.accessSynchronously` beside `UI.access`. Why loom mounts the first segment on the caller
(`R_vt_scheduler`) rather than drain: a drain also runs the access tasks queued earlier; and a
virtual caller, which can't mount it, waits while a platform thread carries the segment, rather
than return early. Why not run the UI fiber on
the request thread itself, released at each park: the answering click would wait in the browser for
the parked request's response (`R_async_push_no_response`), the call could return only at the
fiber's end, and under Karibu no thread is left to click OK. Why exceptions go to the
`ErrorHandler`, inline too, where `UI.accessSynchronously`'s inline path throws to the caller: the
later segments have no caller to throw to, and a UI fiber behaves the same wherever it was started
from. Why not settle the UI fibers it woke before returning: `D_wake_is_access_task`. Why no API
hook before every park for such an epilogue: Vaadin's `ui.beforeClientResponse(...)` already runs
before every UIDL, a push's included (`R_unlock_pushes`), and also sees state changed outside any UI fiber.

## D_two_helpers — Why only two `showAndAwait` helpers, rather than a `confirm(message)`, a Yes/No/Cancel and a text prompt?

Every app builds its own dialogs — texts, themes, button order — so each ready-made helper would
replicate `ConfirmDialog`'s API and still not fit. And Vaadin has no common openable type:
`ConfirmDialog` extends `Component`, not `Dialog`, and `Dialog`, `ConfirmDialog` and `Notification`
each declare their own `open()` / `close()`. So a helper is per type — `Dialog` with the app's own
answer future, `ConfirmDialog` mapped onto `ConfirmDialogOutcome` — and both live in
`BlockingDialogs`, over `UIFibers`, so no runner implements or overrides them. Anything else, a fourth
button or a progress dialog around a job, is the app's own two lines around `parkAndAwait`; the
testapp shows the progress dialog.

## D_loom_servlet — Why does loom ship a servlet that registers nowhere, rather than install its session lock by itself?

`getSessionLock()` is a protected method of `VaadinService`, so only a service subclass can wrap the
lock (`R_vt_lock_identity`) — the app must subclass something, and a servlet is what a Vaadin Boot
or plain-servlet app already declares. `LoomVaadinServlet` carries no `@WebServlet`, so a library jar
never claims `/*` behind the app's back; the app's one-line subclass does. An app with a service
class of its own, Spring's, calls `VirtualThreadAwareLock.wrap()` from its override, and the
first request of a session with an unwrapped lock fails at init (`SessionLockCheck`), naming both
fixes. Why does that check register itself through `META-INF/services`, rather than inside the
servlet or `wrap()`: there it runs only where the lock is already wrapped, and a check the app must
register is skipped by the very app that forgot the wrap. Spring finds it too, and one also declared
as a bean just checks twice (`R_service_init_listeners`). It throws an `Error`, since an
`Exception` there is only logged. Why not seed the lock from an
`HttpSessionListener`: it needs the service name up front, loses Vaadin's instrumented lock
(`SessionLockListener`), relies on the container scanning a `@WebListener` in a library jar (Spring
Boot doesn't), and `VaadinSession.refreshLock()` forbids swapping the lock later. Why no published
Karibu fixture: an app may test with Vaadin's own UI unit testing, so the README shows the override
instead.

## D_loom_jdk_gate — Why does loom refuse to start on Java 21-23, rather than warn, or compile for Java 24+?

There a UI fiber parking inside any monitor deadlocks its session for good (`R_vt_pinning`), a
JDK-internal monitor included, so no code review rules it out. A warning or a README line is how
vaadin-loom#2 happened: nobody reads either until the session hangs. So the runner's constructor
throws, naming JEP 491, and every `UIFibers` call repeats it — unless
`-Dblockingdialogs.uifiber.loom.allowPinningJdk=true`, for shops stuck on 21 LTS that accept the risk. CI's
JDK 21 job sets it, so the loom tests still run on the floor we compile for. Why not `--release 24`:
the same protection as a cryptic `UnsupportedClassVersionError`, with no way out. The cost: the gate
is per JVM, so an app that never parks inside a monitor still has to opt in.

## D_ui_fiber — Why call the parkable unit a "UI fiber", rather than a block, a task or a UI thread?

The code a runner runs — one thread for its whole life, holding the session lock except while
parked — needs a noun of its own. "Block" collided with itself: a virtual thread that *blocks*
inside a `synchronized` *block*, `finally` blocks, a modal that blocks input. "Task" is Vaadin's
access task, "coroutine" Kotlin's, "flow" Vaadin's own name, "strand" is already a verb here. "UI
thread" suggests a `java.lang.Thread` per unit, which is loom's shape but not the concept's. A fiber
yields only where it chooses — here, at a park - which holds whether a virtual or a platform thread
backs it; "UI" in front keeps it from reading as Loom's own virtual thread. So always "UI fiber",
in identifiers too (`checkInUIFiber`), and the code handed in is its `body`.

## D_runner_owns_park — Why does the runner make the thing a UI fiber parks on, rather than the fiber parking on the app's `Future`?

A park is where the session lock is released, and releasing it is the runner's craft: loom
unmounts; a platform-thread runner `await()`s a `Condition` of the session's lock, which releases
every hold without a drain or a push (`R_unlock_pushes`) — hold counting for free. So
`UIFiberRunnerSpi.newCompletable` hands out a `Completable` the runner implements, and every
`Completable.park()` releases the lock while nothing else does: IO, a bare `future.get()` and a
`Thread.sleep()` keep it (`D_loom_holds_the_lock`). That also makes the first park, where
`runUntilFirstPark` returns, a real one and never an IO wait. Why not a `CompletableFuture`: its
async callbacks run on arbitrary threads, and `cancel()` / `obtrudeValue()` are wake-ups the runner
never sees. Why not one designated releasing `Completable` per fiber: a fiber parks many times — a
confirm, then a second dialog — and a second park holding the lock would shut out its own
answering click. The cost: a bare park waiting for the user freezes the session, loudly and under
every runner alike (**One API, any runner**).

## D_wake_is_access_task — Why does a woken UI fiber settle in the session's drain, rather than before the waking call returns?

`Completable.complete()` queues the woken fiber as an access task of its session, so it runs to its
next park or end in the drain before the lock is really released (`R_unlock_pushes`): after the
waking listener, before any UIDL and before the next request. That is Swing's promise exactly — a
modal's caller runs after the OK listener returns, before the next event. The drain loops until
the queue is empty, so a cascade — A wakes B, B wakes C — settles in one go; and every wake counts
without a race, since `complete()` holds the lock and a background thread's lands through
`session.access`. For that every real release of the lock drains first, a later `park()` included;
only the first park inside `runUntilFirstPark` leaves it to the caller's own unlock. Why not an SPI
call that settles every woken fiber before returning: stronger than Swing, but every waker must
remember to call it, a plain listener calling `complete()` included. Why not a scope collecting
what a call woke: whether a wake from another thread belongs to it is a race. The cost: a test
asserting straight after `_click` still needs a roundtrip.

## D_in_fiber_flag — Why does the API itself track whether code runs in a UI fiber, rather than ask the runner?

The answer decides whether a call may park, runs inline or would deadlock, and the API gets it
exact by construction: a thread-local its wrapper sets around `body` and clears around each park —
a parked fiber isn't running, and the SPI lets a runner run another fiber on its thread meanwhile. The
runner keeps a flag of its own, since `runUntilFirstPark` must refuse a call from inside a fiber,
but the API doesn't ask it. Asking would leave one source of truth: a runner bug would send the API
down the wrong branch and silence the runner's own check with it — odd behaviour, no exception.
With two flags set independently, a disagreement either way meets the runner's
`IllegalStateException`: `runUntilFirstPark` refuses a caller inside a fiber, `newCompletable()`
and `park()` one outside. Both rest on the SPI's promise that a fiber keeps one thread
from start to end, which the `CurrentInstance`s need anyway. The cost: two thread-locals, and no
runner mounting a raw `Continuation` on whichever carrier is free — which would strand
`UI.getCurrent()` on the old carrier anyway.

## D_loom_holds_the_lock — Why does loom hold a carrier through a UI fiber's IO, rather than let the lock go at every unmount?

A UI fiber unmounts at any blocking call — a socket read, `sleep`, a contended lock — not only at a
park (`R_vt_unmount_ends_segment`). Ending the access task there would break the SPI's "only
`park()` releases the lock": a JDBC call before the first dialog lets the double click in
(`D_input_exclusion`), and one UI fiber is atomic on one runner, interleaved on another. So the
carrier stays in its access task, holding the lock, for the UI fiber's next continuation. That
costs a platform thread per IO wait — what a plain listener and Swing's EDT pay; Vaadin's lock is
thread-owned and the response needs it anyway. Loom's "a park holds no thread" is for waiting on a
human. Accepted, two new deadlocks. A UI fiber waiting on what a parked one holds — a row locked
across a `confirm()` while another tab of the session updates it — waits out the DB's lock
timeout, as on session-unlock, and as Swing with a `DOCUMENT_MODAL` dialog. A UI fiber waiting on
a lock its own carrier holds — a listener inside `synchronized (cache)` calling `runUntilPark`, the
body taking `cache` too — never ends; Swing's EDT would re-enter it, being one thread. The
session lock is spared: `VirtualThreadAwareLock` makes the UI fiber its carrier's co-owner. Why a
WARN watchdog (`LOCK_HOLD_WARN_SECONDS`) rather than thread dumps: the JVM sees no Java-level
cycle, and the stuck line sits on a virtual thread `jstack` omits.
