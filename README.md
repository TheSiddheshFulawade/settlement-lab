# Settlement Lab

A concurrency-safe settlement engine: move money between accounts concurrently without ever creating
or losing any, under duplicates, downstream failures and overload. Then measure locking and threading
choices. Write-up: https://siddheshfulawade.com/projects/settlement-lab

Status: in progress (v1). See [SPEC.md](SPEC.md) for requirements and the task list.

## How to run

```bash
./mvnw verify
```

On Windows: `mvnw.cmd verify`. Needs JDK 21 or newer (25 recommended). `./mvnw spotless:apply`
fixes formatting.

## Decisions

### Build (L-01)

- **Chosen:** Maven wrapper pinned with a SHA-256, `release 21`, one `verify` gate (enforcer, JaCoCo
  85 %, Spotless, ArchUnit). **Alternative:** Gradle; or separate CI steps for lint and coverage.
  **Why:** Maven's fixed lifecycle is easy to explain and to reproduce; one gate means "green" has one
  meaning locally and in CI. `release 21` keeps the code buildable on 21 while we run on 25.
- **Chosen:** JUnit 5.14 rather than 6.x. **Why:** jqwik 1.10 runs on JUnit Platform 1.14; staying on
  one platform line avoids engine conflicts. See [ADR-0001](docs/adr/0001-dependencies.md).

### Ledger (L-02)

- **Chosen:** one `ReentrantLock` per account, both accounts locked in ascending `AccountId` order.
  **Alternative:** one lock for the whole ledger; `tryLock` with back-off and retry; lock-free CAS
  (L-03, measured later). **Why:** a single global order rules deadlock out by construction, while
  transfers on unrelated accounts still run in parallel. Back-off works but adds retries and
  livelock risk for no gain here.
- **Chosen:** `snapshot()` locks every account (same order) and copies. **Alternative:** read balances
  without locks. **Why:** an unlocked read can see a debit without its credit, so the total would
  look wrong. The cost is a short pause of all transfers per snapshot, acceptable for checks.
- **Chosen:** invalid input is data (`InvalidReason`, `REJECTED_INVALID`), not an exception.
  **Why:** a bad request is normal traffic; the caller needs a reason, not a stack trace.

### CAS ledger (L-03)

- **Chosen:** whole balance table as an immutable `long[]` in an `AtomicReference`, updated with
  `compareAndSet` and retried on conflict. **Alternative:** one `AtomicLong` per account; a
  `StampedLock`. **Why:** per-account atomics cannot debit and credit two accounts atomically
  without extra machinery; one swapped snapshot can, and gives free consistent snapshots. The
  `long[]` (not a map) keeps the per-write copy as cheap as possible. **Trade-off:** copy on every
  write and retry storms under contention, counted by `conflicts()` and measured in L-08.

### Idempotency (L-04)

- **Chosen:** `ConcurrentHashMap<TransferId, CompletableFuture<Outcome>>` with `putIfAbsent`; the first
  caller owns the id, duplicates share its future. **Alternative:** a `synchronized` set of seen ids;
  `computeIfAbsent` running the work inside the map. **Why:** `putIfAbsent` is one atomic step and
  never runs work under a map lock; the future lets a duplicate that arrives mid-flight wait for the
  same answer instead of running again.
- **Chosen:** duplicates get the original outcome (counted as `DUPLICATE` in metrics), not a bare
  `DUPLICATE`. **Why:** a retrying client needs to know what happened to its transfer.
- **Chosen:** unprocessed claims (`QUEUE_FULL`, a failure before applying) are released.
  **Alternative:** remember every outcome. **Why:** otherwise a client that hit back-pressure could
  never retry with the same id. **Trade-off:** no expiry in v1; memory grows with distinct ids.

### Engine, intake and workers (L-05)

- **Chosen:** `ArrayBlockingQueue` with `offer(timeout)` answering `QUEUE_FULL`. **Alternative:** an
  unbounded queue; a blocking `put`; rejecting at once with no wait. **Why:** bounded memory and
  bounded caller wait (REQ-LAB-009); a short wait absorbs small bursts without rejecting them.
- **Chosen:** `PLATFORM` = N platform threads; `VIRTUAL` = virtual thread per job behind a
  `Semaphore(N)`. **Alternative:** an unbounded virtual-thread fan-out. **Why:** the same cap in both
  modes keeps the benchmark fair; unlimited virtual threads would just move contention to the locks.
- **Chosen:** validate and check duplicates in `submit`, before queueing. **Why:** bad and repeated
  requests never use queue space or a worker.
- **Chosen:** `close()` answers queued leftovers with `QUEUE_FULL` and releases their ids.
  **Why:** no caller may wait forever, and a retry must be possible.
- **Chosen:** the listener gets `(Transfer, Outcome)`. **Trade-off:** it does not carry the
  `Posting`; L-06 (publishing to the general ledger) will decide how the engine gets the posting.

### General ledger, faults and outbox (L-06)

- **Chosen:** `Ledger.applyWithResult` returns the recorded posting. **Alternative:** the engine
  builds a posting from the transfer. **Why:** the ledger stays the single source of postings, so
  the GL receives exactly what the ledger holds, sequence numbers included.
- **Chosen:** outbox + single in-order relay, and a GL keyed by transfer id. **Alternative:**
  publish synchronously and fail the transfer when the GL fails. **Why:** the engine stays available
  when the GL is not; at-least-once delivery to an idempotent receiver gives an exactly-once effect.
  **Trade-off:** the v1 outbox is in memory, so a crash loses pending postings.
- **Chosen:** a `PostingPublisher` port in `domain`. **Why:** `pipeline` must not import `gl`; the
  runner wires the two.
- **Chosen:** fault rates as whole percentages, outage as a time window with an injectable clock.
  **Why:** repeatable from a seed, and tests move through the window without waiting.

### Retry and circuit breaker (L-07)

- **Chosen:** Resilience4j `Retry` (3 attempts, exponential back-off from 50 ms, 50 % jitter,
  transient errors only) inside a count-based `CircuitBreaker` (window 20, 50 %, open 2 s), as a
  decorator `GeneralLedgerPort` in front of the outbox. **Alternative:** hand-written retry loops;
  breaker inside retry. **Why:** a well-tested library for subtle state machines; breaker outside
  so an open breaker fails fast and counts whole publishes. **Trade-off:** the first runtime
  dependency (plus `slf4j-api`), kept behind one package by ArchUnit.
- Starting values from SPEC.md; they will be tuned against the runner's numbers in L-08 and L-09.
