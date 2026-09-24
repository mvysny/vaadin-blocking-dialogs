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
