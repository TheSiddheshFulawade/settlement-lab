# Settlement Lab · v1 spec (engine and tests)

Spec for the separate public repo `settlement-lab` (local path `D:\Siddhesh\settlement-lab`).
Decided by ADR-0004 in the portfolio repo. A separate Claude session builds it from this file.
v1 is a plain Java library plus a command-line runner. v2 wraps it in a Spring Boot API
(`api.siddheshfulawade.com`), so **the engine never imports Spring**.

## 0. Rules that carry over from the portfolio

- **Original work.** Do not use any employer design, naming, code, message formats, system names or
  volumes. Use only generic payments terms (account, transfer, posting, ledger, general ledger).
- Tests first for every task. Concurrency code is proven with property and stress tests that check
  invariants, not only examples.
- Exact version pins. Each dependency is listed in the repo's `docs/adr/0001-dependencies.md`.
- Numbers in international format (100,000). Benchmark numbers are from this lab on a named machine,
  never from a production system.
- No secrets or personal data anywhere in the repo.

## 1. Problem

Move money between accounts concurrently without ever creating or losing money, even when deliveries
are duplicated, the downstream general ledger is slow or failing, and load exceeds capacity.
Then measure how two locking strategies and two threading models behave under the same load.

## 2. Scope (v1)

In: in-memory ledger (ordered locking, plus a CAS mode for comparison) · idempotency store ·
bounded queue and worker pool (platform or virtual threads) · fake general ledger with fault
injection · Resilience4j retry and circuit breaker with an outbox · invariant checker · runner that
prints a results table · jqwik property tests and concurrency stress tests · README.

Out (v2 or later): HTTP API, persistence, SSE stream, UI, JMH micro-benchmarks, Docker.

## 3. Requirements (EARS)

- REQ-LAB-001 THE ledger SHALL keep balances as `long` minor units; no floating point anywhere.
- REQ-LAB-002 WHEN a transfer is applied, THE ledger SHALL debit and credit atomically: both
  happen or neither does.
- REQ-LAB-003 IF a transfer would make the source balance negative, THEN THE ledger SHALL reject
  it and change nothing.
- REQ-LAB-004 IF a transfer has amount ≤ 0, an unknown account, or the same source and target,
  THEN THE engine SHALL reject it with a reason, before it is queued.
- REQ-LAB-005 THE sum of all balances SHALL stay equal to the opening total at every snapshot.
- REQ-LAB-006 WHILE transfers run concurrently, including opposing transfers (A→B with B→A),
  THE ordered-locking ledger SHALL not deadlock.
- REQ-LAB-007 THE CAS ledger SHALL satisfy REQ-LAB-002, 003 and 005 without locks.
- REQ-LAB-008 WHEN the same transfer id arrives more than once, including concurrently, THE
  engine SHALL apply it at most once and return the same outcome to every caller.
- REQ-LAB-009 WHILE the queue is full, THE engine SHALL reject new submissions with
  `QUEUE_FULL` within a bounded wait, and never block the caller forever.
- REQ-LAB-010 THE worker pool SHALL run in `PLATFORM` (fixed pool of N) or `VIRTUAL` mode
  (virtual thread per task, concurrency capped at N), chosen by configuration.
- REQ-LAB-011 WHEN a posting succeeds, THE engine SHALL publish it to the general ledger; IF
  publishing fails after retries or the circuit is open, THEN THE engine SHALL keep it in the
  outbox and retry it later.
- REQ-LAB-012 WHEN the engine is drained, every posted transfer SHALL appear in the general
  ledger exactly once (no missing and no duplicate entries).
- REQ-LAB-013 THE fake general ledger SHALL inject faults by profile: added latency, error
  rate, timeouts, duplicate acknowledgements and an outage window, from a fixed seed.
- REQ-LAB-014 THE invariant checker SHALL report each invariant as pass or fail with the first
  counterexample found.
- REQ-LAB-015 THE runner SHALL run a scenario matrix and print a results table, and write the
  same results to `results/results.json` and `results/results.md`.
- REQ-LAB-016 THE domain packages SHALL import only `java.*` (ArchUnit test).

## 4. Design

Base package `com.siddheshfulawade.settlementlab`. Single Maven module, Java 25.

| Package       | Contents                                                                                                                                                                                                               | May import                        |
| ------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | --------------------------------- |
| `domain`      | `AccountId`, `Money` (long minor units), `Transfer`, `Posting`, `Outcome` (`POSTED`, `REJECTED_FUNDS`, `REJECTED_INVALID`, `QUEUE_FULL`, `DUPLICATE`), `Ledger` port, `LockingLedger`, `CasLedger`, `InvariantChecker` | `java.*` only                     |
| `idempotency` | `IdempotencyStore`                                                                                                                                                                                                     | `domain`, `java.*`                |
| `pipeline`    | `SettlementEngine` facade, `BoundedIntake`, `WorkerPool`, `ThreadMode`                                                                                                                                                 | `domain`, `idempotency`, `java.*` |
| `gl`          | `GeneralLedgerPort` (in domain terms), `FakeGeneralLedger`, `FaultProfile`, `Outbox`                                                                                                                                   | `domain`, `java.*`                |
| `resilience`  | `ResilientPublisher`: Resilience4j retry and circuit breaker around `GeneralLedgerPort`                                                                                                                                | `domain`, `gl`, Resilience4j      |
| `runner`      | `Main`, `Scenario`, `ResultsTable`, JSON and Markdown writers                                                                                                                                                          | everything above                  |

### 4.1 Ledgers

- **LockingLedger**: one `ReentrantLock` per account. A transfer locks both accounts **in
  ascending `AccountId` order**, checks funds, then debits and credits. One global order makes a
  wait cycle impossible, so opposing transfers cannot deadlock.
- **CasLedger**: the whole balance table is an immutable snapshot held in an
  `AtomicReference`. A transfer reads the snapshot, builds the next one and calls `compareAndSet`,
  retrying on conflict. That is atomic across both accounts without locks. The cost: copying on
  every write, and retry storms when contention is high. The runner measures both, and the
  README states the trade-off honestly; CAS losing at high contention is a valid result.
- Both expose `snapshot()` (a consistent copy of the balances) for the invariant checker.

### 4.2 Idempotency

`ConcurrentHashMap<TransferId, CompletableFuture<Outcome>>`. The first caller installs a future
with `putIfAbsent` and processes the transfer. Concurrent duplicates get the same future and see the
same outcome. A duplicate returns the original outcome and is counted as `DUPLICATE` in metrics.
No expiry in v1 (memory-bounded by the run size); expiry is noted as a v2 decision.

### 4.3 Intake and workers

`ArrayBlockingQueue` with configurable capacity. `submit()` validates (REQ-LAB-004), then
`offer(timeout)`, then returns `QUEUE_FULL` on timeout (backpressure, REQ-LAB-009). Workers take from
the queue. `PLATFORM` mode uses a fixed pool of N; `VIRTUAL` mode uses
`Executors.newVirtualThreadPerTaskExecutor()` with a `Semaphore(N)` so both modes have the same
concurrency cap and the comparison is fair. Graceful `drain()` and `close()`.

### 4.4 General ledger, faults and resilience

Flow per transfer: idempotency → ledger apply → on `POSTED`, publish `Posting` to the GL through
`ResilientPublisher`.

- `FakeGeneralLedger` stores entries keyed by transfer id (so a duplicate publish is detectable,
  not hidden) and applies a `FaultProfile`: `NONE`, `FLAKY` (10 % errors, 5 % timeouts, added latency),
  `DUPLICATE_ACKS`, `OUTAGE` (fails everything for a window). Seeded `RandomGenerator` for repeatable runs.
- `ResilientPublisher`: Resilience4j `Retry` (3 attempts, exponential backoff with jitter, only on
  transient errors) inside a `CircuitBreaker` (count window 20, failure rate 50 %, open wait 2 s).
  Starting values; tune and record in the README.
- When a publish fails or the breaker is open, the posting goes to the `Outbox`. A single relay
  retries outbox entries in order. `drain()` waits until the outbox is empty or a deadline passes.
- The ledger posting is the source of truth; the GL is eventually consistent with it (REQ-LAB-012).

### 4.5 Invariant checker

Checks after a run (and optionally on sampled snapshots during it): conservation of total, no
negative balance, each transfer id posted at most once, ledger postings equal accepted transfers, and
GL entries equal ledger postings after drain. Returns a report with pass or fail per invariant and the
first counterexample.

### 4.6 Runner

`Main` runs a matrix: ledger {LOCKING, CAS} × threads {PLATFORM, VIRTUAL} × faults {NONE, FLAKY,
OUTAGE}, same seed and workload per cell (for example 10,000 accounts and 1,000,000 transfers with
a configurable hot-account skew). Columns: ledger, threads, faults, transfers, throughput/s,
p50 and p99 latency, rejected, duplicates, retries, breaker opens, outbox peak, invariants (pass or fail).
Prints JDK version, CPU and core count with the table. Writes `results/results.json` and
`results/results.md`; the portfolio write-up reads those numbers.

### 4.7 v2 seam

`SettlementEngine` is the only type v2 needs: `submit(Transfer) → CompletionStage<Outcome>`,
`snapshot()`, `metrics()` and a listener for posting events (v2 turns it into SSE). Keep it free of
framework types.

## 5. Build and quality (replaces portfolio tasks T-09 to T-13)

- Maven wrapper with `distributionSha256Sum`; Java 25 via `maven.compiler.release` and the
  enforcer plugin.
- Dependencies (exact latest stable versions, checked on Maven Central at creation): Resilience4j
  (`resilience4j-retry`, `resilience4j-circuitbreaker`); test: JUnit Jupiter, AssertJ, jqwik,
  Awaitility, ArchUnit. Nothing else at runtime.
- JaCoCo line coverage gate ≥ 85 % on `verify`, excluding `runner`.
- Spotless with palantir-java-format, checked on `verify`.
- ArchUnit tests: REQ-LAB-016 plus the package rules in section 4; no `org.springframework` anywhere.
- `./mvnw verify` is the single gate. Optional: one GitHub Actions workflow running it on PRs.

## 6. Test plan

- Unit tests per class, named `REQ-LAB-NNN <behaviour>`.
- jqwik properties, run against **both** ledgers through one parameterised suite: for random
  accounts and transfers, total conserved, no negative balance, rejection leaves state unchanged.
- Stress: K threads start behind a `CountDownLatch`, run M transfers, join with a timeout, then
  assert invariants. Opposing-transfer deadlock test under a timeout (REQ-LAB-006).
- Idempotency: the same id submitted by 50 threads at once posts once, and every caller sees the
  same outcome.
- Backpressure: a full queue returns `QUEUE_FULL` within the bounded wait.
- Faults: each profile, followed by drain, satisfies REQ-LAB-012; with `OUTAGE`, the breaker opens and
  later closes. Use Awaitility; never `Thread.sleep` for synchronisation.

## 7. Tasks (feature-sized, test-first)

- [ ] L-01 Build skeleton: wrapper, pom, enforcer, JaCoCo, Spotless, ArchUnit rules, deps ADR, CLAUDE.md for the repo
- [ ] L-02 Domain types and `LockingLedger`, plus the invariant checker and property and stress suites (REQ 001–006, 014)
- [ ] L-03 `CasLedger` on the same suites (REQ 007)
- [ ] L-04 `IdempotencyStore` with concurrent duplicate tests (REQ 008)
- [ ] L-05 Intake, worker pool, `SettlementEngine` facade, both thread modes (REQ 004, 009, 010)
- [ ] L-06 Fake GL with fault profiles, outbox and relay (REQ 011–013)
- [ ] L-07 `ResilientPublisher` (retry and circuit breaker), wired into the engine (REQ 011, 012)
- [ ] L-08 Runner, scenario matrix, results table and the JSON and Markdown output (REQ 015)
- [ ] L-09 README, a final run on a named machine, results committed

## 8. README outline

Problem · Design (packages, the flow diagram, ledgers, idempotency, backpressure, resilience) ·
Decisions and trade-offs (ordered locking vs CAS, platform vs virtual threads, outbox vs synchronous
GL, at-most-once apply with exactly-once effect) · Results (table from `results/results.md`, machine and
JDK named) · How to run (`./mvnw verify`, `./mvnw exec:java` or the jar) · What v2 adds · Link to the
write-up: https://siddheshfulawade.com/projects/settlement-lab

## 9. Hand-back to the portfolio

When L-08 has a real run, the portfolio's V1-10 write-up uses `results/results.md`. If it is not ready
by Sunday 22 Nov 2026, the write-up ships with a "results coming" section and is updated in v1.1.
