# Vaadin Blocking Dialogs

A library that lets Vaadin Flow code block on a dialog - `if (confirm("Delete?")) delete();` - the
way Swing's `JOptionPane` does, instead of splitting the logic into callbacks. The code is suspended
until the user answers while the browser keeps receiving UI updates. How it is suspended is a
pluggable runner: virtual threads, or a platform thread parked with the session lock released.

> **Work in progress.** Nothing is published to Maven Central yet, and the API is not settled.

Read [Vaadin and Blocking Dialogs](https://mvysny.github.io/vaadin-blocking-dialogs/) on why this is
such a hard thing to do in a web framework.

## Modules

| Artifact | What it is |
|---|---|
| `vaadin-blocking-dialogs` | The runner-neutral API your code is written against. |
| `vaadin-uifiber-spi` | The SPI a runner implements; your code never calls it. |
| `vaadin-uifiber-loom` | The virtual-thread runner, grown out of the [vaadin-loom](https://github.com/mvysny/vaadin-loom) prototype. |
| `vaadin-uifiber-session-unlock` | Planned: parks an ordinary platform thread with the Vaadin session lock released. |

Group id: `com.github.mvysny.vaadin-blocking-dialogs`. The `testapp-*` modules are the demo, not published.

## Installation

Add the API and exactly one runner to your app, e.g. with Gradle:

```kotlin
dependencies {
    implementation("com.github.mvysny.vaadin-blocking-dialogs:vaadin-blocking-dialogs:0.1")
    implementation("com.github.mvysny.vaadin-blocking-dialogs:vaadin-uifiber-loom:0.1")
}
```

or Maven:

```xml
<dependency>
    <groupId>com.github.mvysny.vaadin-blocking-dialogs</groupId>
    <artifactId>vaadin-blocking-dialogs</artifactId>
    <version>0.1</version>
</dependency>
<dependency>
    <groupId>com.github.mvysny.vaadin-blocking-dialogs</groupId>
    <artifactId>vaadin-uifiber-loom</artifactId>
    <version>0.1</version>
</dependency>
```

Your app brings its own Vaadin; the library doesn't pull one in.

## Requirements

Every runner needs `@Push` on your `AppShellConfigurator`: the dialog travels to the browser while
your code is blocked, and only push can carry it there.

The loom runner additionally needs:

- **Its servlet**, whose session lock a virtual thread can take. `LoomVaadinServlet` registers
  nowhere by itself; subclass it:
  ```java
  @WebServlet(urlPatterns = "/*", asyncSupported = true)
  public class AppServlet extends LoomVaadinServlet {}
  ```
  An app with a `VaadinService` subclass of its own - Spring's `SpringVaadinServletService` - or a
  mocked service in its UI unit tests routes the session lock through the wrapper instead:
  ```java
  @Override
  protected Lock getSessionLock(WrappedSession wrappedSession) {
      return VirtualThreadAwareLock.wrap(this, wrappedSession, super.getSessionLock(wrappedSession));
  }
  ```
  Without it, every session's first request fails with an `Error` naming the fix.
- **Java 24+ at runtime** (it compiles for Java 21). On Java 21-23 a virtual thread that blocks
  inside a `synchronized` block deadlocks the session - see
  [JEP 491](https://openjdk.org/jeps/491) and [vaadin-loom#2](https://github.com/mvysny/vaadin-loom/issues/2) -
  so the runner refuses to start there, unless you accept the risk with
  `-Dblockingdialogs.uifiber.loom.allowPinningJdk=true`.
- **`--add-opens java.base/java.lang=ALL-UNNAMED`** on the JVM: the runner reflects into the JDK
  to run virtual threads on Vaadin's UI "thread" ([JDK-8308541](https://bugs.openjdk.org/browse/JDK-8308541)).
- **HTTP requests served by platform threads**, not virtual ones - with Vaadin Boot,
  `new VaadinBoot().useVirtualThreadsIfAvailable(false)`. Virtual request threads are not verified
  yet: there a platform thread carries each step of a UI fiber while the request thread waits.

## Usage

Start a *UI fiber* from a listener - UI code that may park until the user answers, the session lock
released meanwhile. Inside it, a dialog call returns the user's answer:

```java
button.addClickListener(e -> UIFibers.runLater(() -> {
    ConfirmDialog dialog = new ConfirmDialog();
    dialog.setText("Delete " + file + "?");
    dialog.setCancelable(true);
    if (BlockingDialogs.showAndAwait(dialog) == ConfirmDialogOutcome.CONFIRM) {
        delete(file);
    }
}));
```

A UI fiber ends quietly when its dialog goes away unanswered: the user navigates away, closes the tab,
or the session expires. Its `finally` blocks run on the way out.

### Beyond dialogs

Underneath, a UI fiber can wait for any `CompletableFuture`, not only a dialog's answer.
`UIFibers.parkAndAwait(anchor, future)` parks until the future completes. The anchor is the
component the wait belongs to, and the wait dies with it: a Save button waiting on its own progress
bar, or a background job's result shown in a progress dialog. From a background thread,
`UIFibers.accessSynchronously(ui, body)` runs a UI fiber and waits for it, so a job can ask the
user something halfway through. When the listener's own code after the call must see what the UI fiber
did, `UIFibers.runUntilPark(body)` returns once the UI fiber has opened its first dialog, or
ended.

## Limits

- **A waiting UI fiber does not survive session serialization.** A parked thread can't be serialized,
  so a session with an open blocking dialog loses that dialog's wait under session persistence or
  replication.

## Running the demo

```bash
./gradlew :testapp-loom:run
```

Then open [http://localhost:8080](http://localhost:8080): "Save changes before closing?", a delete
loop with Yes to all, a background import behind a progress dialog, F5 with a dialog open, and
the edge cases - each page shows its own source, blocking beside callbacks, and a "Try this" list
of what to check by hand.

## Credits

The session-unlock runner is [Matthias Perktold's](https://github.com/mperktold/blocking-dialogs/)
idea; the loom runner follows the [vaadin.com blog post](https://vaadin.com/blog/tackling-blocking-dialogs-in-web-applications-with-vaadin).

## License

Licensed under the [Apache License, Version 2.0](LICENSE).
