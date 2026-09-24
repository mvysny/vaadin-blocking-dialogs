# Vaadin Blocking Dialogs

A library that lets Vaadin Flow code block on a dialog - `if (confirm("Delete?")) delete();` - the
way Swing's `JOptionPane` does, instead of splitting the logic into callbacks. The code is suspended
until the user answers while the browser keeps receiving UI updates. How it is suspended is a
pluggable strategy: virtual threads, or a platform thread parked with the session lock released.

> **Work in progress.** Nothing is published to Maven Central yet, and the API is not settled.

Read [Vaadin and Blocking Dialogs](https://mvysny.github.io/vaadin-blocking-dialogs/) on why this is
such a hard thing to do in a web framework.

## Modules

| Artifact | What it is |
|---|---|
| `vaadin-blocking-dialogs` | The strategy-neutral API your code is written against. |
| `vaadin-blocking-dialogs-loom` | The virtual-thread strategy, grown out of the [vaadin-loom](https://github.com/mvysny/vaadin-loom) prototype. |
| `vaadin-blocking-dialogs-session-unlock` | Planned: parks an ordinary platform thread with the Vaadin session lock released. |

Group id: `com.github.mvysny.vaadin-blocking-dialogs`. The `testapp` module is a demo, not published.

## Requirements

Every strategy needs `@Push` on your `AppShellConfigurator`: the dialog travels to the browser while
your code is blocked, and only push can carry it there.

The loom strategy additionally needs:

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
  Without it, every `runLater` throws, naming the fix.
- **Java 24+ at runtime** (it compiles for Java 21). On Java 21-23 a virtual thread that blocks
  inside a `synchronized` block deadlocks the session - see
  [JEP 491](https://openjdk.org/jeps/491) and [vaadin-loom#2](https://github.com/mvysny/vaadin-loom/issues/2) -
  so the strategy refuses to start there, unless you accept the risk with
  `-Dblockingdialogs.loom.allowPinningJdk=true`.
- **`--add-opens java.base/java.lang=ALL-UNNAMED`** on the JVM: the strategy reflects into the JDK
  to run virtual threads on Vaadin's UI "thread" ([JDK-8308541](https://bugs.openjdk.org/browse/JDK-8308541)).
- **HTTP requests served by platform threads**, not virtual ones - with Vaadin Boot,
  `new VaadinBoot().useVirtualThreadsIfAvailable(false)`. On a virtual request thread blocks still
  work, but a double-clicked button may run its blocking listener twice.

## Usage

Start a *block* from a listener; inside it, a dialog call returns the user's answer:

```java
button.addClickListener(e -> BlockingDialogs.runLater(() -> {
    ConfirmDialog dialog = new ConfirmDialog();
    dialog.setText("Delete " + file + "?");
    dialog.setCancelable(true);
    if (BlockingDialogs.showAndAwait(dialog) == ConfirmDialogOutcome.CONFIRM) {
        delete(file);
    }
}));
```

A block ends quietly when its dialog goes away unanswered: the user navigates away, closes the tab,
or the session expires. Its `finally` blocks run on the way out.

### Beyond dialogs

Underneath, a block can wait for any `CompletableFuture`, not only a dialog's answer.
`BlockingDialogs.parkAndAwait(anchor, future)` parks until the future completes. The anchor is the
component the wait belongs to, and the wait dies with it: a Save button waiting on its own progress
bar, or a background job's result shown in a progress dialog. From a background thread,
`BlockingDialogs.accessSynchronously(ui, block)` runs a block and waits for it, so a job can ask the
user something halfway through. When the listener's own code after the call must see what the block
did, `BlockingDialogs.runUntilPark(block)` returns once the block has opened its first dialog, or
ended.

## Limits

- **A waiting block does not survive session serialization.** A parked thread can't be serialized,
  so a session with an open blocking dialog loses that dialog's wait under session persistence or
  replication.

## Running the demo

```bash
./gradlew :testapp:run
```

Then open [http://localhost:8080](http://localhost:8080).

## Credits

The session-unlock strategy is [Matthias Perktold's](https://github.com/mperktold/blocking-dialogs/)
idea; the loom strategy follows the [vaadin.com blog post](https://vaadin.com/blog/tackling-blocking-dialogs-in-web-applications-with-vaadin).

## License

Licensed under the [MIT License](LICENSE).
