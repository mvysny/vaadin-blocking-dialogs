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
the API is only what both can implement — a block may not lean on what one strategy gives for free,
such as a bare `future.get()` that loom tolerates.

## D_anchored_wait — Why does a wait end when its anchor detaches, rather than with an executor scope per UI, session or tab?

A wait belongs to a component — a dialog anchors its own answer — and that component's detach is
the one signal every death of a wait sends: navigating away, closing the dialog, a session destroy
and a tab close all detach it (`R_session_destroy_detaches`). A scope — a registry of parked blocks
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
API — and then "which strategy runs this block?" has more than one answer, and every call must route
by executor. With exactly one on the classpath the strategy is a dependency choice (**One API, any
strategy**), `BlockingDialogs` is statics over `BlockingExecutor.get()`, and none or two is an error
on every call. Tests get their strategy the same way, from a `META-INF/services` file in test
resources; a strategy that needs configuration reads it itself, as SPI providers do. The cost: one
classpath runs one strategy, so demoing both takes one app per strategy.

## D_input_exclusion — Why does `runLater` keep the session's other requests out until the block's first park, rather than just queue the block?

The double-clicked Save button: the second click must find the first click's dialog already open,
so that Vaadin's server-side modality drops it — Swing's "a modal blocks input from
`setVisible(true)`". Without it every blocking action runs twice on a fast double click. Loom gives it by construction: the block's
first segment runs inside the click request's ultimate unlock, before the lock is really released
(`R_unlock_pushes`). A strategy whose block starts after the request responds pays for it: the
request waits for the block's first park or end before responding — short and bounded, unlike the
endless park of `R_async_push_no_response`. Not promised: a first segment that ends without parking
releases the lock like any listener, and a later click runs the listener again.

## D_two_helpers — Why only two `showAndAwait` helpers, rather than a `confirm(message)`, a Yes/No/Cancel and a text prompt?

Every app builds its own dialogs — texts, themes, button order — so each ready-made helper would
replicate `ConfirmDialog`'s API and still not fit. And Vaadin has no common openable type:
`ConfirmDialog` extends `Component`, not `Dialog`, and `Dialog`, `ConfirmDialog` and `Notification`
each declare their own `open()` / `close()`. So a helper is per type — `Dialog` with the app's own
answer future, `ConfirmDialog` mapped onto `ConfirmDialogOutcome` — and both live only on the
`BlockingDialogs` facade, so no strategy implements or overrides them. Anything else, a fourth
button or a progress dialog around a job, is the app's own two lines around `parkAndAwait`; the
testapp shows the progress dialog.
