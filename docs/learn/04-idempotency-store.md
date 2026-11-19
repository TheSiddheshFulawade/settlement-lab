# 04 · IdempotencyStore (L-04)

## What it is, in plain words

The same transfer can arrive twice (a client retry, a redelivered message). It must move money once,
and both callers must get the same answer. `IdempotencyStore` keeps one `CompletableFuture<Outcome>`
per `TransferId` in a `ConcurrentHashMap`.

- `claim(id)` makes a fresh future and calls `putIfAbsent`. That call is atomic: of any number of
  threads racing on one id, exactly one gets `null` back. That thread is the **owner**; it does the
  work and completes the future. Everyone else is a **duplicate**: they get the existing future and
  are counted in `duplicates()` (shown as `DUPLICATE` in metrics), but they still receive the real
  outcome, for example `POSTED`.
- Duplicates get `future.copy()`, a read-only view: if a duplicate calls `complete`, only its copy
  changes, never what other callers see.
- `release(id, claim)` lets the owner forget an id that was **never processed** (for example
  `QUEUE_FULL` in L-05), so a retry is not answered "queue full" forever. Only the current owner can
  release (`remove(key, value)` is atomic and compares the future).
- `execute(id, work)` is claim plus run: if the work throws, nothing was applied, so the claim is
  released and the future fails; a retry can run.
- No expiry in v1: one entry per distinct id in a run. Expiry (time or size bound) is a v2 decision.

Proven by: 50 threads on one id, repeated 20 times (posts once, 49 duplicates, all see `POSTED`);
1,000 ids sent by 8 threads each, checked by `InvariantChecker`; a jqwik property against the
obvious sequential model. Replacing `putIfAbsent` with `get` then `put` failed 6 of 21 runs: races
are probabilistic, which is why the test repeats.

## Interview questions

1. **Why `putIfAbsent` and not `containsKey` then `put`?**
   Check-then-act across two calls is a race: two threads both see "absent" and both put, so the
   work runs twice. `putIfAbsent` does the check and the insert as one atomic step on the map.
2. **Why store a future instead of the outcome?**
   A duplicate can arrive while the first is still running. With only outcomes stored, it would find
   nothing and run the work again, or have to spin. A future is a placeholder installed *before* the
   work starts, so latecomers wait on the same result.
3. **Why not `computeIfAbsent(id, k -> runWork())`?**
   It would hold a lock on that map bin while the work runs (slow ledger, later a queue), blocking
   other ids in the same bin, and it must not be re-entered. Installing a future is instant; the
   work runs outside the map.
4. **Which outcomes should be remembered, and which released?**
   Remember anything that reflects a decision about the transfer (`POSTED`, `REJECTED_FUNDS`,
   `REJECTED_INVALID`): replaying it is correct. Release outcomes where nothing was processed
   (`QUEUE_FULL`, an exception before applying), so a retry gets a real chance.
5. **What is the memory risk, and how would you bound it in production?**
   One entry per distinct id, forever. Bound it with a time-to-live matching the client's retry
   window (or a size cap with eviction), and persist keys with the posting so a restart does not
   forget them. Our run size bounds it in v1.
