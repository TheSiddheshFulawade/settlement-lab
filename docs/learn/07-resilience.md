# 07 · Retry and circuit breaker (L-07)

## What it is, in plain words

`ResilientPublisher` is a `GeneralLedgerPort` that wraps the real one. It sits between the outbox
and the general ledger: `OutboxPublisher(new ResilientPublisher(gl, config), retryInterval)`.

- **Retry (inner):** up to 3 attempts per publish, waiting 50 ms, then about 100 ms, each spread
  ±50 % at random (**jitter**) so that many callers that failed together do not retry together.
  Only `GeneralLedgerException` (transient: error, timeout, outage) is retried. A bug, such as a
  conflicting posting, fails at once.
- **Circuit breaker (outer):** watches the last 20 publishes (count-based window). When at least half
  failed (after a minimum number of calls), it **opens**: every publish fails fast with
  `CIRCUIT_OPEN`, without touching the general ledger, for 2 s. Then it goes **half-open** and lets a
  few probe calls through; if they pass, it **closes**; if not, it opens again.
- **Why breaker outside retry:** one publish, retries included, is one breaker call, so the failure
  rate means "publishes that failed", not "attempts". And an open breaker is never retried.
- **When it gives up**, it throws; the outbox keeps the posting and the relay tries again later. So
  the two layers split the work: Resilience4j handles short blips quickly, the outbox handles long
  outages without losing anything.
- `retries()`, `breakerOpens()` and `breakerState()` feed the runner's results table (L-08).
- ArchUnit: only the `resilience` package may import Resilience4j.

## Interview questions

1. **Why add jitter to exponential back-off?**
   Without it, every client that failed at the same moment retries at the same moments too, creating
   synchronised waves of load (a thundering herd) on a service that is trying to recover.
2. **Why only retry transient errors?**
   Retrying a permanent failure (a bug, a validation error) cannot succeed; it wastes time and load,
   and can hide the bug. Only failures that may pass on a second try are worth retrying.
3. **Why put the circuit breaker outside the retry?**
   So the breaker counts whole publishes, and so an open breaker fails immediately instead of being
   retried three times with back-off. Retry outside would keep hammering, the opposite of the point.
4. **What does the circuit breaker protect: us, or the general ledger?**
   Both. The GL gets breathing room to recover instead of a flood of doomed calls. We stop spending
   worker time and back-off waits on calls that will fail, and fail fast into the outbox instead.
5. **If the outbox already retries forever, why have Resilience4j at all?**
   Different time scales. Retry absorbs a short blip within one publish, so most postings reach the
   GL at once and the outbox stays small. The breaker detects a real outage quickly and stops the
   waste. The outbox is the safety net for anything longer.
