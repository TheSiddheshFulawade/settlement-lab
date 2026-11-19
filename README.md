# Settlement Lab

A concurrency-safe settlement engine: move money between accounts concurrently without ever creating
or losing any, under duplicates, downstream failures and overload. Then measure locking and threading
choices. Write-up: https://siddheshfulawade.com/projects/settlement-lab

v1 is a plain Java library and a command-line runner. [SPEC.md](SPEC.md) has the requirements
(REQ-LAB-001 to 016), the design and the task list.

## Problem

Many callers move money between accounts at the same time. The engine must never create or lose
money, never apply the same transfer twice even when it arrives twice, stay responsive when the
downstream general ledger (GL) is slow or down, and push back instead of falling over when load
exceeds capacity. Then: how do two locking strategies and two threading models compare?

## Design

| Package | What it holds | May import |
| ------- | ------------- | ---------- |
| `domain` | `Money` (long minor units), `Transfer`, `Posting`, `Ledger` port, `LockingLedger`, `CasLedger`, `InvariantChecker`, `PostingPublisher` port | `java.*` only |
| `idempotency` | `IdempotencyStore` | `domain` |
| `pipeline` | `SettlementEngine`, bounded intake, `WorkerPool` (`PLATFORM` / `VIRTUAL`) | `domain`, `idempotency` |
| `gl` | `GeneralLedgerPort`, `FakeGeneralLedger`, `FaultProfile`, `OutboxPublisher` | `domain` |
| `resilience` | `ResilientPublisher` (Resilience4j retry inside a circuit breaker) | `domain`, `gl`, Resilience4j |
| `runner` | `Main`, `Workload`, `ScenarioRunner`, results writers | everything |

ArchUnit enforces the last column, no Spring anywhere, and no package cycles.

```text
submit(transfer)
  -> IdempotencyStore      duplicate id? return the original outcome
  -> TransferValidator     invalid? REJECTED_INVALID, never queued
  -> bounded queue         full for longer than offerTimeout? QUEUE_FULL (back-pressure)
  -> worker (N, platform or virtual)
       -> Ledger.applyWithResult   POSTED / REJECTED_FUNDS, atomically
       -> caller's future completes          (does not wait for the GL)
       -> OutboxPublisher.publish(posting)   returns at once
            -> sender thread -> ResilientPublisher (retry, breaker) -> general ledger
            -> on failure: outbox -> relay retries until the GL accepts it
```

- **Ledgers.** `LockingLedger`: one `ReentrantLock` per account, both accounts locked in ascending
  id order, so a wait cycle (deadlock) is impossible. `CasLedger`: the whole balance table is an
  immutable array swapped with `compareAndSet`, retried on conflict; no locks at all.
- **Idempotency.** One `CompletableFuture` per transfer id, installed with `putIfAbsent`: exactly one
  caller processes it, every duplicate gets the same answer.
- **Back-pressure.** An `ArrayBlockingQueue` and `offer(timeout)`: a caller waits at most 100 ms, then
  hears `QUEUE_FULL` and may retry with the same id.
- **Resilience.** The ledger posting is the source of truth; the GL is eventually consistent. Retry
  absorbs blips, the circuit breaker fails fast during outages, the outbox never loses a posting,
  and the GL is keyed by transfer id, so a resend is harmless: exactly-once effect.
- **Proof.** jqwik properties (including agreement with a sequential model) and stress tests run
  against both ledgers; every runner cell ends with `InvariantChecker`. Study notes for each module
  are in [docs/learn](docs/learn).

## Results

One run of `./mvnw -q compile exec:java` (defaults: 10,000 accounts, 100,000 transfers, 20 % of
transfers within 20 hot accounts, 2 % duplicate resends, seed 20261119, 8 workers, 64 closed-loop
clients). Full output: [results/results.md](results/results.md) and
[results/results.json](results/results.json).

**Machine:** cloud container, Intel Xeon @ 2.10 GHz, 4 cores, Linux amd64, **JDK 21.0.13**. JDK 25
was not available in that container, so this run is on 21; a run on JDK 25 is still to do.
Numbers are from this lab on that machine, a single run, never from a production system.

| Ledger | Threads | Faults | Transfers | Throughput/s | p50 µs | p99 µs | Rejected | Duplicates | Retries | Breaker opens | Outbox peak | Invariants |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| LOCKING | PLATFORM | NONE | 100,000 | 248,861 | 183 | 2,008 | 853 | 2,006 | 0 | 0 | 0 | PASS |
| LOCKING | PLATFORM | FLAKY | 100,000 | 215,823 | 197 | 2,680 | 825 | 2,006 | 16,739 | 0 | 3 | PASS |
| LOCKING | PLATFORM | OUTAGE | 100,000 | 172,734 | 228 | 2,840 | 808 | 2,006 | 54 | 1 | 90,036 | PASS |
| LOCKING | VIRTUAL | NONE | 100,000 | 181,706 | 340 | 800 | 825 | 2,006 | 0 | 0 | 0 | PASS |
| LOCKING | VIRTUAL | FLAKY | 100,000 | 225,608 | 237 | 735 | 814 | 2,006 | 16,763 | 0 | 3 | PASS |
| LOCKING | VIRTUAL | OUTAGE | 100,000 | 165,981 | 368 | 1,342 | 832 | 2,006 | 52 | 1 | 86,480 | PASS |
| CAS | PLATFORM | NONE | 100,000 | 54,027 | 878 | 9,488 | 820 | 2,006 | 0 | 0 | 0 | PASS |
| CAS | PLATFORM | FLAKY | 100,000 | 49,865 | 886 | 8,784 | 830 | 2,006 | 16,777 | 0 | 3 | PASS |
| CAS | PLATFORM | OUTAGE | 100,000 | 59,859 | 829 | 6,282 | 813 | 2,006 | 52 | 1 | 94,167 | PASS |
| CAS | VIRTUAL | NONE | 100,000 | 55,465 | 1,011 | 3,779 | 826 | 2,006 | 0 | 0 | 0 | PASS |
| CAS | VIRTUAL | FLAKY | 100,000 | 51,813 | 1,016 | 3,507 | 821 | 2,006 | 16,762 | 0 | 3 | PASS |
| CAS | VIRTUAL | OUTAGE | 100,000 | 54,297 | 1,046 | 3,409 | 802 | 2,006 | 52 | 1 | 95,181 | PASS |

What the numbers say (and what they do not):

- **Ordered locking beat CAS by about 3 to 4.5 times** on throughput (2.8× to 4.6× per cell), with
  a lower p99 in every pair. With 10,000
  accounts, every CAS write copies an 80 KB balance array, and a failed swap throws that copy away
  and starts again; per-account locks touch two accounts only. CAS losing here is a valid result:
  its cost grows with the table size and with contention. With a small table and many readers the
  balance could tip the other way; this run did not test that.
- **Platform vs virtual threads: no clear throughput winner** at the same cap (N = 8). Platform was
  faster for locking with no faults (249k vs 182k/s); the other pairs are within about 10 %. The
  work is short and CPU-bound, so virtual threads have little blocking to hide. **Virtual threads
  had the lower p99 in all six pairs** (for example 800 vs 2,008 µs), with a somewhat higher p50.
  One run on 4 cores: the direction is worth a repeat run, the exact ratios are not to be trusted.
- **Faults cost some throughput, not a collapse**: for locking, FLAKY cost up to about 13 % and
  OUTAGE up to about 30 %; CAS moved within noise. Before publishing moved off the worker threads,
  FLAKY ran at about 730 transfers/s. FLAKY needed about 16,700 retries, but only 3 postings ever
  reached the outbox. OUTAGE opened the
  breaker once per cell; the outbox held up to about 95,000 postings (the 1 s outage outlasts the
  run) and drained them all afterwards.
- **Every invariant passed in every cell**: total conserved, no negative balance, each transfer
  posted at most once, postings explain the balances, postings match accepted transfers, and the
  GL holds exactly the ledger's postings.

## How to run

```bash
./mvnw verify
```

On Windows: `mvnw.cmd verify`. Needs JDK 21 or newer (25 recommended). `./mvnw spotless:apply`
fixes formatting. `verify` is the single gate: compile, tests, 85 % line coverage, format and
architecture rules.

Run the scenario matrix (writes `results/results.json` and `results/results.md`; exits 1 if any
invariant fails):

```bash
./mvnw -q compile exec:java -Dexec.args="--transfers 100000"
```

Options: `--accounts`, `--transfers`, `--hot-accounts`, `--hot-percent`, `--duplicate-percent`,
`--seed`, `--out`.

## What v2 adds

A Spring Boot API at `api.siddheshfulawade.com` that wraps `SettlementEngine` (submit, snapshot,
metrics), a server-sent event stream from the engine listener, persistence (a transactional outbox
in a database instead of memory), idempotency-key expiry, and a small UI. The engine stays
framework-free.

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
- **Chosen:** direct publish first, outbox + single in-order relay only for failures, and a GL keyed
  by transfer id. (Queueing every posting behind a non-empty outbox was tried and reversed: the L-08
  runner showed the single relay became the bottleneck under `FLAKY`.) **Alternative:**
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

### Runner and results (L-08)

- **Chosen:** a seeded, index-addressed workload and closed-loop clients (64 virtual threads), one
  warm-up per ledger, nearest-rank p50 and p99, and invariants checked in every cell.
  **Alternative:** JMH (out of scope for v1, SPEC.md section 2); an open-loop load generator.
  **Why:** the question is end-to-end behaviour of the whole engine under faults, not a micro
  benchmark; closed loop measures capacity rather than queue overflow.
- **Chosen:** JSON written by hand. **Why:** a dozen flat fields do not justify a JSON library.
- **Found by measuring:** the first run exposed the outbox bottleneck and GL latency on worker
  threads (see L-06). Both fixed test-first before any number was committed.
