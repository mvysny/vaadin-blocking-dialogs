# A SwingWorker-like `awaitInBackground(task)`

Since `D_loom_holds_the_lock`, a UI fiber doing long IO freezes its session on every runner, as a
Swing listener freezes the EDT. An app that wants the UI live meanwhile — a progress bar, a Cancel
button — has no accidental way left under loom; it needs a deliberate one, as Swing has
`SwingWorker`.

Sketch: `BlockingDialogs.awaitInBackground(task)` runs `task` on an executor and parks the UI fiber
on its result through `parkAndAwait`, so the lock is released on purpose. Ideally behind a modal
progress dialog, so `D_input_exclusion` holds: the push updates reach the browser, the clicks
outside the dialog don't.

## Open questions

- `Q_executor`: whose executor — a library default (virtual threads), or the app's?
- `Q_progress_api`: does the task get a progress callback that marshals onto the UI, as
  `SwingWorker.publish()` does, or does the app use `ui.access()` itself?
- `Q_cancel`: the dialog's Cancel interrupts the task, and the park throws what?
