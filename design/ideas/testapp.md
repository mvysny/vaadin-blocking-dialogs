# A testapp worth demoing

The testapp is vaadin-loom's demo, ported: one button and two nested "Are you sure?" confirms ending
in a notification. It proves the mechanism works, but not why anybody would want it, and it exercises none of the
edge cases that make blocking hard. The testapp here is also the system test of both strategies
(its Karibu tests drive it), so the demo *is* the regression suite — every scenario below should be
a test too. Graduates when the testapp is built: nothing durable expected beyond a module-map
line and the README's "Running the demo".

## Frame

**One demo app per strategy** — `testapp-loom`, and `testapp-session-unlock` once that module
exists — because the strategy is found through the SPI and more than one on the classpath is an
error (`D_spi_exactly_one`). Each is an `AppLayout` with a `SideNav`, one route per scenario. The two apps
should run **the same demo components, differing only in the strategy jar on their classpath** —
that is **One API, any strategy** made visible, and it stops the demos drifting apart. So the demo
components live in a shared, unpublished module (`testapp-common`?) that depends only on
`vaadin-blocking-dialogs`; each app is `Main` + its strategy dependency. The progress-dialog-around-
a-job helper lives there too, as the example of the outcome-future pattern (`BlockingDialogs`' class doc, "Cancellation").

## Scenario candidates

1. **"Save changes before closing?"** — the canonical Swing flow: Yes / No / Cancel, then on Yes a
   file-name prompt, then "File exists, overwrite?". Three dialogs, a `String` result, a cancel
   path — written as ten lines of straight-line code. The strongest single pitch.
2. **Loop with a dialog per item** — "Delete 5 files": for each file, "Delete *x*?" with
   Yes / No / Yes to all / Cancel. A loop over dialogs is where callback style really hurts; show it.
3. **Callback vs blocking, side by side** — next to each scenario, its source in both styles (the
   blocking one ~10 lines, the callback one ~40 with nesting). The pedagogical punchline; source
   shown as static text, not reflection magic.
4. **Awaiting something that is not a dialog** — "Import" kicks off a background job; the UI fiber
   shows a progress dialog and `await`s the job's future, then continues with the result. Shows
   `await` is general.
5. **The browser stays live** — a clock / counter updated by push from a background thread keeps
   ticking while a dialog is open; a second browser tab on the same session stays usable. Proves
   the session lock really is released.
6. **Edge cases as buttons** — navigate away with a dialog open (the UI fiber is killed, its `finally`
   closes the dialog, nothing reported as an error); an exception thrown after the dialog (reaches
   the session `ErrorHandler`, shown as a notification); a dialog opened inside `synchronized`
   (works on JDK 24+; explains `R_vt_pinning` on older JDKs); nested UI fibers; a double-clicked Save
   whose second click finds the dialog open and is dropped (`D_input_exclusion`, both strategies).
7. **Under the hood panel** — which thread runs the UI fiber (a virtual thread's name vs a worker's),
   how many UI fibers are parked right now, session lock hold count. Makes the strategies' difference
   tangible, and `Q_scale_budget` in the session-unlock idea measurable.

## Open questions

- **`Q_scenario_pick`** — which of the above make the cut? Leaning 1 + 2 as the main demo, 5 as an
  always-on header, 6 as a "torture" tab, 3 inline; 4 and 7 if cheap.
- **`Q_shared_demo_module`** — a `testapp-common` module holding the demo components, or duplicate
  them per app? A module keeps the apps honest (they can't diverge); duplication is one module fewer.
- **`Q_live_demo`** — host it (v-herd, like vaadin-loom)? Then the Docker image needs JDK 24+ and the
  `--add-opens`, and production mode must work in CI.
- **`Q_browser_tests`** — Karibu can't see `Q_modal_gap` or the loading indicator, which are the
  bugs `R_async_push_no_response` is about. Worth a small Playwright suite against the running
  testapp for those two only? That is a new dependency and a new CI job.
