# v2-04 · Server-sent event stream (V2-04)

## What it is, in plain words

```bash
curl -N localhost:8080/api/v1/events
# :connected
# event:outcome
# data:{"id":"t-1","outcome":"POSTED","source":1,"target":2,"amount":100}
```

- **`EventHub`** is the engine's `SettlementListener`. When a worker finishes a transfer it calls
  `onOutcome` (`EventHub.java:99`), which only does `buffer.offer(event)` for each subscriber: never
  blocking.
- **Each subscriber** (`Subscription`, `:36`) has a bounded buffer and one virtual thread (the
  "sender") that polls the buffer and writes to the connection (`deliver`, `:49`). If a write fails
  the client has gone and it is removed (`:59`).
- **Buffer full means drop** (`:105-108`). A full buffer means the client is too slow. We count it
  (`dropped`), end its stream, and it may reconnect. We do not skip single events: a stream with silent
  gaps is worse than a stream that ends.
- **`EventStreamController`** returns an `SseEmitter` and plugs it into the hub through a tiny `Sink`
  interface (`send`, `close`), so `EventHub` is tested without HTTP. It sends a `:connected` comment
  first (`EventStreamController.java:31`): servers send SSE headers only with the first bytes, so
  without it a client would not know the stream is open until the first transfer.
- No SSE `id:` field: the transfer id is client input and a line separator in it makes the emitter
  throw, which would drop every subscriber. The id travels in the JSON data instead.
- Shutdown: `EventStreamShutdown` closes the hub on `ContextClosedEvent`, which Spring publishes before
  the web server's graceful phase starts waiting. Otherwise an idle stream would hold shutdown for the
  whole phase timeout. Accepted transfers still drain; events for a closed hub reach nobody.

## The core concept: the engine thread must never wait

`onOutcome` runs on a worker. Whatever a client does (stall, vanish, throw on close), the worker may
only do a non-blocking `offer` and `drop`. Four defects were fixed to make that true everywhere.

```
 worker thread                       per-subscriber virtual threads
 onOutcome -> offer(buffer) ----->   sender: poll buffer -> sink.send (may block on the socket)
      |  full?                       closer: sink.close()    (may block or throw)
      +--> drop() -- CAS open true->false, remove, interrupt sender, start closer, return
```

### 1. The subscribe race (`:36-47`, `:83-93`)

Before the fix, a subscription could be visible in the `subscriptions` set while its `sender` thread
was not yet assigned. A worker that dropped it in that moment could touch a missing thread and throw on
the engine thread. Now `sender` is `final` and created `unstarted` in the constructor, so it is set
before anyone else can see the subscription. It is started only after the checks (`:93`). A cancel
before the start is safe: interrupting an unstarted thread does nothing, and `deliver` checks `open`
first, so it exits at once.

### 2. Close runs on its own virtual thread, exactly once (`:117-123`)

`drop` does `open.compareAndSet(true, false)`. Only the one caller that wins the swap removes the
subscription, interrupts the sender and runs `sink.close()` on a fresh virtual thread. Why not close
inline? `SseEmitter.complete()` can block or throw, and the caller may be the worker. Why a separate
thread and not the sender? The sender may be stuck inside a write.

### 3. A throwing close is contained (`:126-132`)

`closeQuietly` catches `RuntimeException` (for example "emitter already completed"). The exception dies
on the closer thread. It cannot reach the worker or the other subscribers.

### 4. Subscribe after close, and why both orders are safe (`:78-96`, `:144-147`)

`close()` sets `closed = true` and then iterates the set. `subscribe` adds to the set and then
re-checks `closed`. Two threads, two orders:

```
 A: close sets closed, iterates   | B: subscribe adds, re-checks closed
 B adds before A iterates  -> A's iteration sees it and drops it
 B adds after A iterates   -> B's re-check sees closed=true and drops it itself
 both happen               -> drop() is idempotent (CAS), sink closed once
```

Either way no subscription outlives `close()`. If `closed` is already true on entry (`:79`), the sink is
closed on the caller's own thread and a no-op handle is returned.

### 5. A stuck write leaks at most its own thread

A socket write does not react to interrupt. If the sender is stuck, `drop` still removes the
subscription, closes the sink on another thread and returns. The sender stays until the write ends, then
sees `open == false` and exits. That costs one virtual thread, and only that subscriber's cost.
`stuckWriteCannotHoldTheHostage` proves `onOutcome` and `hub.close()` return anyway.

## Why SSE, not WebSockets, not polling

The flow is one way. SSE is plain HTTP, passes proxies, browsers reconnect themselves. WebSockets need
a second protocol and pay off only for two-way traffic. Polling wastes requests and misses the moment
things happen.

## Why the tests pace the publisher and await the close

With a buffer of 1, an unpaced burst would rightly drop the healthy subscriber too (the engine is
faster than any sender). So `blockingCloseDoesNotBlockTheEngine` publishes one event, then awaits the
healthy sink having it (`EventHubFailureTest.java:132-136`). The close now runs on its own thread, so a
test cannot assert "closed" straight after `onOutcome`. It uses Awaitility (`:139`), never `Thread.sleep`.
`publishWithin` runs the publish on another thread, so a hang fails the test instead of the build.

## Open decision: no subscriber limit yet

Nothing caps how many clients may subscribe. Each costs a buffer and a virtual thread. Before deploying
we must decide: a cap (reject with 503), per-client authentication, or both. It is a product choice, so
it waits for the user (hard gate).

## TODO-ME

`EventHubTest.java:133` has `cancelStopsDelivery` for you to write: cancel through the returned
`Runnable`, then publish. Not filled in here.

## Interview questions

1. **Why SSE rather than WebSockets?**
   One-way data; plain HTTP; automatic browser reconnect; no extra library. WebSockets for two-way.
2. **How does a slow client avoid slowing the engine down?**
   The worker only offers into a bounded buffer (never blocks). A per-client virtual thread does the
   slow write. A full buffer drops that client.
3. **Why does `drop` close the sink on a new virtual thread and use compareAndSet?**
   Close can block or throw and the sender can be stuck in a write, so neither the worker nor the
   sender may run it. The CAS means that, however many callers race (worker, cancel, hub close), one
   wins and the sink closes once.
4. **How does subscribe stay safe against a concurrent `close()`?**
   `close` sets `closed` then iterates; `subscribe` adds then re-checks `closed`. Either the
   iteration sees the add or the re-check sees the flag; both is fine because `drop` is idempotent.
5. **What happens to a sender blocked in a socket write when its client is dropped?**
   It cannot be interrupted, so it lingers until the write returns or fails, then sees `open == false`
   and exits. It leaks at most its own virtual thread; the engine and others are unaffected.

## What breaks if...

...`sender` were assigned after `subscriptions.add(...)` (a plain non-final field set later)? Which
test would catch it, and what would the engine thread see? Try it, run
`subscribeRaceNeverBreaksTheEngineThread`, and explain the failure. Then say what breaks if `subscribe`
did not re-check `closed`.
