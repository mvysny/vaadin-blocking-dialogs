# Loom on virtual request threads, and virtual threads waking a UI fiber

A continuation reaching a *virtual* drainer runs on a platform handoff thread while the drainer
waits, holding the session lock (`LoomUIFiberRunner.SessionCarrier.mount`; `VirtualDrainerProbeTest`
shows it for a fiber start, a wake-up, a cascade and `runUntilPark`, JBR 25.0.4). Two questions
follow from it.

Why it matters to SB-Emulators, read from its code and JDK 25's sources, not run: its request
threads are platform, but a foreign virtual thread waking a parked fiber while the lock is free
drains the queue itself, and on Linux JDK 25's pollers are virtual (`Poller.Mode.VTHREAD_POLLERS`),
so a fiber resumed after IO may be drained by one.

## Open questions

- `Q_virtual_request_threads` — with the handoff, loom may run on virtual HTTP request threads: the
  request thread waits while a platform thread carries each segment. Karibu has no request
  threads, so check it in a real container (the testapp with `useVirtualThreadsIfAvailable(true)`)
  before lifting the AGENTS.md invariant and the README requirement.
- `Q_poller_submit` — a virtual poller that unparks a fiber now waits out a whole UI segment,
  holding up IO for other sockets. Route a submit made from a virtual thread that isn't one of our
  fibers through a platform thread's `session.access`, so a poller never takes the session lock?
  Our own fibers submit directly, their pretend lock only queuing. Unmeasured.

Graduates into `D_run_until_park`'s virtual-caller clause and the README's requirements if the
container check passes, or into a why-not there if it doesn't.
