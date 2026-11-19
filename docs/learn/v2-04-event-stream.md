# v2-04 · Server-sent event stream (V2-04)

## What it is, in plain words

```bash
curl -N localhost:8080/api/v1/events
# :connected
# event:outcome
# id:t-1
# data:{"id":"t-1","outcome":"POSTED","source":1,"target":2,"amount":100}
```

- **`EventHub`** is the engine's `SettlementListener`. When a worker finishes a transfer it calls
  `onOutcome`, which only does `buffer.offer(event)` for each subscriber: never blocking.
- **Each subscriber** has a bounded buffer (1,024 events) and one virtual thread that takes from
  the buffer and writes to the connection. If the buffer is full the client is too slow, so the hub
  drops it (its stream ends; it can reconnect). If a write fails, the client has gone, and it is
  removed.
- **`EventStreamController`** returns an `SseEmitter` and plugs it into the hub through a tiny `Sink`
  interface, so `EventHub` is tested without HTTP: in-order delivery, a slow subscriber dropped
  while a fast one gets everything and `onOutcome` stays fast, a failing one removed, and close.
- The controller sends a `:connected` comment first: servers only send SSE headers with the first
  bytes, so without it a client would not know the stream is open until the first transfer.
- Shutdown order: Spring closes the runtime first (it depends on the hub), so the engine stops
  producing events before the hub closes the streams.

## Interview questions

1. **Why SSE rather than WebSockets?**
   The data flows one way, server to client. SSE is plain HTTP, works through proxies, reconnects by
   itself in browsers, and needs no extra library. WebSockets earn their cost for two-way traffic.
2. **How does a slow client avoid slowing the engine down?**
   The worker never writes to a socket. It only offers into a bounded in-memory buffer, which never
   blocks; a separate virtual thread per client does the slow writing. A full buffer drops the client.
3. **Why drop a slow subscriber instead of dropping some of its events?**
   A stream with silent gaps is worse than a stream that ends: the client cannot tell what it missed.
   Ending the stream makes it reconnect and, if it cares, read current state (balances, metrics).
4. **Why a virtual thread per subscriber?**
   Each one spends its life blocked on the buffer or on the network. Virtual threads make that cheap,
   so thousands of subscribers do not need thousands of platform threads.
5. **What would you add for production?**
   Event ids that let a client resume (`Last-Event-ID`) from a durable log, a cap on subscribers,
   authentication, and metrics for dropped subscribers. The hub already counts drops.
