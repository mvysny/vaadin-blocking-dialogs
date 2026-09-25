# Host the demo live

Host `testapp-loom` (and later `testapp-session-unlock`) somewhere public, as vaadin-loom is on
v-herd. Deferred: not until the demo has been tried by hand.

What it takes:

- A Docker image on JDK 24+ (the loom runner refuses 21-23, `D_loom_jdk_gate`), its JVM with the
  `--add-opens` of the loom invariant in `AGENTS.md`.
- Production mode building in CI — the build already runs `-Pvaadin.productionMode`, but nothing
  starts the result.
- Visitors don't meet each other's files: `FileStore` and `FiberLog` are per session already.
