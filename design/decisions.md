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

## D_pluggable_strategy — Why one API over pluggable blocking strategies rather than one blocking mechanism?

Blocking a Vaadin listener takes two things at once: the code parks until the user answers, and
the session lock is released so the dialog reaches the browser and the answering click can be
processed. Two mechanisms do that, and each pays what the other doesn't. Virtual threads park
without holding a thread, but need JDK 24+ (`R_vt_pinning`), reflection into the JDK plus
`--add-opens` (`R_vt_scheduler`), a session-lock wrapper (`R_vt_lock_identity`) and platform request
threads. A platform thread parked with the session lock released needs none of that, but holds one
thread per open dialog and must run on a worker so the request can respond
(`R_async_push_no_response`). So app code sees only `vaadin-blocking-dialogs`, and the strategy is
picked where the app is wired (**One API, any strategy**). Why not loom only: its JDK floor and its
reach into JDK internals are what a conservative line-of-business app cannot take. Why not
session-unlock only: it is one probe old, and gives up virtual threads' scale. The cost we carry:
the API is only what both can implement — a UI fiber may not lean on what one strategy gives for free,
such as a bare `future.get()` that loom tolerates.

## D_anchored_wait — Why does a wait end when its anchor detaches, rather than with an executor scope per UI, session or tab?

A wait belongs to a component — a dialog anchors its own answer — and that component's detach is
the one signal every death of a wait sends: navigating away, closing the dialog, a session destroy
and a tab close all detach it (`R_session_destroy_detaches`). A scope — a registry of parked UI fibers
per UI or session, killed from outside — would duplicate that signal as state both strategies keep
in step, so there is none, and no backstop either. The verdict waits for the detaching UI's next
response, because F5 on a `@PreserveOnRefresh` route detaches the view before it re-attaches it
(`R_preserve_migration`). Why not a `VaadinRequestInterceptor.requestEnd` hook for that verdict: it
needs a `VaadinServiceInitListener` shipped in our jar, and still a fallback for a destroy outside a
request. Why no anchorless `parkAndAwait`: a wait that names no owner can only leak. The cost: a
closed `@PreserveOnRefresh` tab is noticed only at heartbeat expiry — free under loom, a held worker
thread under session-unlock.

## D_spi_exactly_one — Why is the strategy found through `ServiceLoader`, exactly one, rather than handed in by the app?

A setter, or an executor the app builds, is global mutable state that apps take up as a wiring
API — and then "which strategy runs this UI fiber?" has more than one answer, and every call must route
by executor. With exactly one on the classpath the strategy is a dependency choice (**One API, any
strategy**), `BlockingDialogs` is statics over `BlockingExecutor.get()`, and none or two is an error
on every call. Tests get their strategy the same way, from a `META-INF/services` file in test
resources; a strategy that needs configuration reads it itself, as SPI providers do. The cost: one
classpath runs one strategy, so demoing both takes one app per strategy.

## D_input_exclusion — Why does `runLater` keep the session's other requests out until the UI fiber's first park, rather than just queue the UI fiber?

The double-clicked Save button: the second click must find the first click's dialog already open,
so that Vaadin's server-side modality drops it — Swing's "a modal blocks input from
`setVisible(true)`". Without it every blocking action runs twice on a fast double click. Loom gives it by construction: the UI fiber's
first segment runs inside the click request's ultimate unlock, before the lock is really released
(`R_unlock_pushes`). A strategy whose UI fiber starts after the request responds pays for it: the
request waits for the UI fiber's first park or end before responding — short and bounded, unlike the
endless park of `R_async_push_no_response`. Not promised: a first segment that ends without parking
releases the lock like any listener, and a later click runs the listener again.

## D_run_until_park — Why `runUntilPark` beside `runLater`, rather than one entry point that returns after the first park?

Code after the call sometimes must see what the UI fiber did. SB-Emulators runs every Swing listener
as a UI fiber, follows it with an epilogue that reconciles state, and its tests read that state
without a Karibu lookup — the only thing draining the access queue after `_click`. Draining it at
the call site works under loom by accident and not under session-unlock (**One API, any
strategy**), whose request owes the first-park wait of `D_input_exclusion` anyway. Why not make
`runLater` wait: inside a UI fiber it can't — the new UI fiber needs the lock its caller holds — so
`runUntilPark` runs it inline, parks included. A different promise, a different name, as
`UI.accessSynchronously` beside `UI.access`. Why loom mounts the first segment on the caller
(`R_vt_scheduler`) rather than drain: a drain also runs the access tasks queued earlier; and a
virtual caller, which can't mount it, throws rather than return early. Why exceptions go to the
`ErrorHandler`, inline too: the later segments have no caller to throw to, and a UI fiber behaves the
same wherever it was started from.

## D_two_helpers — Why only two `showAndAwait` helpers, rather than a `confirm(message)`, a Yes/No/Cancel and a text prompt?

Every app builds its own dialogs — texts, themes, button order — so each ready-made helper would
replicate `ConfirmDialog`'s API and still not fit. And Vaadin has no common openable type:
`ConfirmDialog` extends `Component`, not `Dialog`, and `Dialog`, `ConfirmDialog` and `Notification`
each declare their own `open()` / `close()`. So a helper is per type — `Dialog` with the app's own
answer future, `ConfirmDialog` mapped onto `ConfirmDialogOutcome` — and both live only on the
`BlockingDialogs` facade, so no strategy implements or overrides them. Anything else, a fourth
button or a progress dialog around a job, is the app's own two lines around `parkAndAwait`; the
testapp shows the progress dialog.

## D_loom_servlet — Why does loom ship a servlet that registers nowhere, rather than install its session lock by itself?

`getSessionLock()` is a protected method of `VaadinService`, so only a service subclass can wrap the
lock (`R_vt_lock_identity`) — the app must subclass something, and a servlet is what a Vaadin Boot
or plain-servlet app already declares. `LoomVaadinServlet` carries no `@WebServlet`, so a library jar
never claims `/*` behind the app's back; the app's one-line subclass does. An app with a service
class of its own, Spring's, calls `VirtualThreadAwareLock.wrap()` from its override, and every
`runLater` refuses an unwrapped lock, naming both fixes. Why not seed the lock from an
`HttpSessionListener`: it needs the service name up front, loses Vaadin's instrumented lock
(`SessionLockListener`), relies on the container scanning a `@WebListener` in a library jar (Spring
Boot doesn't), and `VaadinSession.refreshLock()` forbids swapping the lock later. Why no published
Karibu fixture: an app may test with Vaadin's own UI unit testing, so the README shows the override
instead.

## D_loom_jdk_gate — Why does loom refuse to start on Java 21-23, rather than warn, or compile for Java 24+?

There a UI fiber parking inside any monitor deadlocks its session for good (`R_vt_pinning`), a
JDK-internal monitor included, so no code review rules it out. A warning or a README line is how
vaadin-loom#2 happened: nobody reads either until the session hangs. So the executor's constructor
throws, naming JEP 491, and `BlockingExecutor.get()` repeats it on every call — unless
`-Dblockingdialogs.uifiber.loom.allowPinningJdk=true`, for shops stuck on 21 LTS that accept the risk. CI's
JDK 21 job sets it, so the loom tests still run on the floor we compile for. Why not `--release 24`:
the same protection as a cryptic `UnsupportedClassVersionError`, with no way out. The cost: the gate
is per JVM, so an app that never parks inside a monitor still has to opt in.

## D_ui_fiber — Why call the parkable unit a "UI fiber", rather than a block, a task or a UI thread?

The code a strategy runs — one thread for its whole life, holding the session lock except while
parked — needs a noun of its own. "Block" collided with itself: a virtual thread that *blocks*
inside a `synchronized` *block*, `finally` blocks, a modal that blocks input. "Task" is Vaadin's
access task, "coroutine" Kotlin's, "flow" Vaadin's own name, "strand" is already a verb here. "UI
thread" suggests a `java.lang.Thread` per unit, which is loom's shape but not the concept's. A fiber
yields only where it chooses — here, at a park - which holds whether a virtual or a platform thread
backs it; "UI" in front keeps it from reading as Loom's own virtual thread. So always "UI fiber",
in identifiers too (`checkInUIFiber`), and the code handed in is its `body`.
