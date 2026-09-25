# Vaadin Blocking Dialogs — AGENTS.md

## What this is

A library that lets Vaadin Flow code block on a dialog - `if (confirm("Delete?")) delete();` - the
way Swing's `JOptionPane` does, instead of splitting the logic into callbacks. The code is suspended
until the user answers while the browser keeps receiving UI updates. How it is suspended is a
pluggable strategy: virtual threads, or a platform thread parked with the session lock released.

## Promises

- **Blocking code reads like Swing.** A dialog call returns the user's answer on the caller's own stack; never a callback.
- **One API, any strategy.** Code written against `vaadin-blocking-dialogs` runs unchanged on every strategy; switching is a wiring change. See `D_pluggable_strategy`.

## Design docs

| File | Owns | Loaded |
|---|---|---|
| `README.md` | the pitch, the modules, the requirements an app must meet, how to run the demo | — |
| `CONTRIBUTING.md` | the release steps | — |
| `AGENTS.md` (this) | promises, invariants, the module map, conventions, commands | every turn |
| `design/decisions.md` | why this and not that — `D_` entries, FAQ-shaped | lazy |
| `design/research.md` | what Vaadin and the JDK actually do — `R_` entries, each claim with provenance | lazy |
| `design/ideas/` | not-yet-acted-on ideas, one per file; deleted on graduation, their nuggets moved to the rows above | lazy |
| doc comments | what one symbol does and why it is shaped so | at the symbol |

Every fact lives in exactly one of these; the others link to it.

## Invariants

- **Vaadin is `compileOnly` in the published modules.** A bundled Vaadin clashes with the app's own version.
- **Every app and test serving a blocking dialog has `@Push`.** Without it the dialog never reaches the browser while the code is blocked; see `R_unlock_pushes`.
- **Loom: HTTP requests are served by platform threads.** A continuation can't mount on a virtual one (`R_vt_scheduler`), so the UI fiber's first segment is handed off past the request, losing input exclusion (`D_input_exclusion`).
- **Loom: every `VaadinService` routes `getSessionLock()` through `VirtualThreadAwareLock.wrap()`** — the testapp's servlet extends `LoomVaadinServlet`, Karibu tests use `MockVirtualThreadAwareServlet`. Without it a UI virtual thread taking the session lock recurses into `StackOverflowError`; see `R_vt_lock_identity`.
- **Loom: CI's JDK 21 job passes `-Dblockingdialogs.uifiber.loom.allowPinningJdk=true`**, which the root build forwards to every test JVM; without it the strategy refuses to start there (`D_loom_jdk_gate`).
- **Loom: every JVM running it has `--add-opens java.base/java.lang=ALL-UNNAMED`** — tests, `:testapp:run`, the distribution. The scheduler reflection fails without it; see `R_vt_scheduler`.
- **Loom: a test that parks inside `synchronized` stays `@EnabledForJreRange(minVersion = 24)` while CI runs JDK 21.** On 21-23 it does not fail, it hangs the test JVM; see `R_vt_pinning`.

## Module map

- `vaadin-blocking-dialogs` — the strategy-neutral API app code is written against; published.
- `vaadin-uifiber-spi` — `UIFiberRunnerSpi`, what a strategy implements and no app calls; published. Nothing uses it yet: the API and loom still speak `BlockingExecutor` until `design/ideas/spi.md` lands.
- `vaadin-uifiber-loom` — the virtual-thread strategy, ported from `../vaadin-loom`; published. Its test fixtures (`MockVirtualThreadAwareServlet`) are not.
- `testapp` — Vaadin Boot demo on the loom strategy, one app per strategy since a classpath holds one (`D_spi_exactly_one`); never published.

## Conventions

- **Pure Java, no Kotlin** — production code and tests alike.
- **Java 21 bytecode; the loom strategy needs JDK 24+ at runtime.** CI builds on 21 to keep the floor honest.
- **Tests: JUnit Jupiter + Karibu-Testing through the Java `LocatorJ` API**, in-JVM without a browser; no mocking library.
- **Nullability is annotated** with JetBrains `@NotNull` / `@Nullable` on every public parameter and return value.
- **Dependency versions live in `gradle/libs.versions.toml`**, never in a module's `build.gradle.kts`.
- **A published module calls `configureMavenCentral("<artifactId>")`**; the artifactId is its directory name, the package `com.github.mvysny.blockingdialogs` for `vaadin-blocking-dialogs`, `com.github.mvysny.blockingdialogs.uifiber.<x>` for `vaadin-uifiber-<x>`.
- **Every source and build file opens with the MIT header, `Copyright 2026 Martin Vysny`** — code ported from vaadin-loom included, its `Vaadin Ltd.` header replaced; copy it from `build.gradle.kts`.
- **The unit a strategy runs is a *UI fiber*, never a "block"**, in prose and identifiers (`isInUIFiber`); the `Runnable` handed in is its `body`. See `D_ui_fiber`.
- **Pre-1.0: break APIs freely.**

## Commands

- `./gradlew` — clean + build (the default tasks): every module's tests and `design/verify_design_tripwires.sh`. CI runs it on push and PR in production mode, JDK 21 and 25 × Oracle, Corretto, Temurin, plus the tripwire as a job of its own (`.github/workflows/gradle.yml`).
- `./gradlew :vaadin-uifiber-loom:test --tests "*SomeTest"` — one test class by pattern.
- `./gradlew :testapp:run` — the demo in embedded Jetty, http://localhost:8080.
- `./gradlew clean build publish closeAndReleaseStagingRepositories` — release to Maven Central; the full steps are in `CONTRIBUTING.md`.

## Skills this project follows

- **Component-oriented:** self-sufficient components that reach services directly, no MVC layers; the `cop` skill has the rules. Read it before designing the testapp's components.
- **Karibu-Testing:** browserless Vaadin tests with `MockVaadin`, `_get` / `LocatorJ` lookups; the `karibu-testing` skill has the helpers.
- **Ideas folder:** one idea per file in `design/ideas/`, `Q_` slugs for open questions, deleted once acted on; the `ideas-folder` skill has the graduation procedure.

## Maintenance of this file

Loaded every turn; cap 34 KB, a module's own `AGENTS.md` 10 KB. Over it, in this order:
delete what has no home — status, history, class lists, what the code already says; trim
each line to its fact plus one clause and send the explanation home — why →
`design/decisions.md`, how across symbols → `design/architecture.md`, how in one symbol →
its doc comment, what upstream does → `design/research.md`; only then a module's own
`AGENTS.md`, peripheral modules first, never the core. Never paraphrase a lazy entry into a
line here. `design/verify_design_tripwires.sh` checks the caps and the cites.
