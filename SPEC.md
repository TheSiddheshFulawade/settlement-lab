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

- [x] L-01 Build skeleton: wrapper, pom, enforcer, JaCoCo, Spotless, ArchUnit rules, deps ADR, CLAUDE.md for the repo
- [x] L-02 Domain types and `LockingLedger`, plus the invariant checker and property and stress suites (REQ 001–006, 014)
- [x] L-03 `CasLedger` on the same suites (REQ 007)
- [x] L-04 `IdempotencyStore` with concurrent duplicate tests (REQ 008)
- [x] L-05 Intake, worker pool, `SettlementEngine` facade, both thread modes (REQ 004, 009, 010)
- [x] L-06 Fake GL with fault profiles, outbox and relay (REQ 011–013)
- [x] L-07 `ResilientPublisher` (retry and circuit breaker), wired into the engine (REQ 011, 012)
- [x] L-08 Runner, scenario matrix, results table and the JSON and Markdown output (REQ 015)
- [x] L-09 README, a final run on a named machine, results committed

## 8. README outline

Problem · Design (packages, the flow diagram, ledgers, idempotency, backpressure, resilience) ·
Decisions and trade-offs (ordered locking vs CAS, platform vs virtual threads, outbox vs synchronous
GL, at-most-once apply with exactly-once effect) · Results (table from `results/results.md`, machine and
JDK named) · How to run (`./mvnw verify`, `./mvnw exec:java` or the jar) · What v2 adds · Link to the
write-up: https://siddheshfulawade.com/projects/settlement-lab

## 9. Hand-back to the portfolio

When L-08 has a real run, the portfolio's V1-10 write-up uses `results/results.md`. If it is not ready
by Sunday 22 Nov 2026, the write-up ships with a "results coming" section and is updated in v1.1.

---

# Settlement Lab · v2 spec (HTTP API)

Written 2026-11-19 at the start of v2, after v1 (sections 1 to 9) was complete. Same rules as
section 0. The engine module stays exactly as framework-free as in v1.

## 10. Scope (v2)

In: a Spring Boot API module that wraps `SettlementEngine`: submit a transfer, read a balance, read
metrics, and a server-sent event (SSE) stream of outcomes · configuration of ledger, thread mode,
sizes and fault profile · graceful shutdown · API tests (MockMvc and over real HTTP) · README.

Out (v3 or later): persistence and a database outbox, authentication, the UI, Docker. **Deploying to
`api.siddheshfulawade.com` needs the user's explicit yes** (cloud resources and money, CLAUDE.md), so
it is listed (V2-06) but not done without it.

## 11. Requirements (EARS)

- REQ-API-001 WHEN a client POSTs a transfer (`id`, `source`, `target`, `amount` in minor units), THE
  API SHALL submit it to the engine and answer with its outcome: `POSTED` 201, `REJECTED_FUNDS` 422,
  `REJECTED_INVALID` 400 with the reason, `QUEUE_FULL` 503 with `Retry-After`.
- REQ-API-002 WHEN the same transfer id is POSTed again, THE API SHALL return the original outcome and
  status, and the ledger SHALL change once (REQ-LAB-008 over HTTP).
- REQ-API-003 IF a request body is malformed or misses a field, THEN THE API SHALL answer 400 with an
  RFC 9457 problem detail and SHALL NOT submit anything.
- REQ-API-004 THE API SHALL serve one account's balance (404 if unknown) and the engine, outbox and
  circuit-breaker metrics.
- REQ-API-005 THE API SHALL stream every processed transfer's outcome as SSE to subscribed clients;
  a slow or gone client SHALL NOT slow down or break the engine.
- REQ-API-006 WHEN the application stops, THE API SHALL stop accepting transfers, drain the engine
  and the outbox within a deadline, and close them.
- REQ-API-007 THE engine module SHALL NOT depend on Spring (ArchUnit in the engine module, and the
  module boundary in Maven).

## 12. Design

Maven parent with two modules:

| Module | Contents | Depends on |
| ------ | -------- | ---------- |
| `engine` | all of v1 (sections 4 to 6), unchanged, with its own tests and JUnit 5.14 | Resilience4j |
| `api` | Spring Boot app: `TransferController`, `AccountController`, `MetricsController`, `EventStream`, `EngineConfiguration`, `SettlementProperties` | `engine`, Spring Boot (its BOM manages the api's test libraries) |

- `EngineConfiguration` builds ledger → fake GL → `ResilientPublisher` → `OutboxPublisher` →
  `SettlementEngine` from `settlement.*` properties and closes them in reverse on shutdown.
- Controllers return `CompletableFuture` so a request thread never blocks on the engine; with virtual
  threads enabled (`spring.threads.virtual.enabled`) blocking would be cheap anyway.
- A balance read uses a new `Ledger.balance(AccountId)` (one account, not a full snapshot).
- SSE: the engine listener hands each outcome to a bounded buffer per subscriber; a full buffer drops
  that subscriber, never blocks a worker.

## 13. Tasks (v2)

- [x] V2-01 Multi-module build (parent, `engine`, `api`), app boots with the engine wired from properties, graceful shutdown, CI (REQ-API-006, 007)
- [x] V2-02 `POST /api/v1/transfers` with outcome mapping, idempotent replay and problem details (REQ-API-001 to 003)
- [x] V2-03 `GET /api/v1/accounts/{id}` via `Ledger.balance`, `GET /api/v1/metrics` (REQ-API-004)
- [x] V2-04 `GET /api/v1/events` SSE stream with bounded per-subscriber buffers (REQ-API-005)
- [ ] V2-05 README v2 section, how to run, an HTTP load check with invariants
- [ ] V2-06 Deploy to `api.siddheshfulawade.com` (only with the user's explicit yes). To verify there: real one-CPU Cloud Run run time (FLAKY and OUTAGE at 20,000, two runs at once; 14.1 s at most on Linux CI runners, and the report must stay within 60 s), CPU between requests, and the Cloudflare ranges

# Settlement Lab · v3 spec (PostgreSQL ledger)

Written 2026-11-21, per ADR-0002. Same rules as section 0. The engine module stays framework-free
and database-free: PostgreSQL lives in a new module behind the existing `Ledger` port. The user
lifted the feature pause for v3 on 2026-11-21 and for v2 on 2026-11-23 (live demo by 1 Dec 2026):
v3 tasks run first, back to back, then V2-04 to V2-06. Each task ends with its own PR, merged by `git-ops` on green CI.

## 14. Scope (v3)

In: a `ledger-postgres` module with `PostgresLedger` in two modes (pessimistic row locks,
optimistic version column) · idempotency by database constraint, surviving restarts and shared by
several engine instances · a transactional outbox table and a relay · Flyway migrations · the same
property and stress suites as the in-memory ledgers, run against a real PostgreSQL with
Testcontainers · runner cells for both modes · README Decisions and learn notes.

Out: wiring into the `api` module and deploying to Neon/Cloud Run (that is V2-06 work, with the
user's explicit yes), multi-region, sharding, Oracle (ADR-0002 explains why; the learn notes give
the Oracle equivalents).

## 15. Requirements (EARS)

- REQ-DB-001 THE `PostgresLedger` SHALL implement `Ledger` and pass the same property and stress
  suites as `LockingLedger` and `CasLedger` (REQ-LAB-002, 003, 005, 006), in both modes.
- REQ-DB-002 THE schema SHALL store money as `BIGINT` minor units with `CHECK (balance >= 0)`; no
  floating-point or `NUMERIC` money columns.
- REQ-DB-003 WHEN a transfer is applied in pessimistic mode, THE ledger SHALL lock both account rows
  with `SELECT … FOR UPDATE` in ascending account id order, inside one transaction, and SHALL NOT
  deadlock with opposing transfers.
- REQ-DB-004 WHEN a transfer is applied in optimistic mode, THE ledger SHALL update each account
  with `… WHERE id = ? AND version = ?`; IF either update changes zero rows, THEN it SHALL roll back
  and retry with jittered back-off, and SHALL NOT lose an update.
- REQ-DB-004a IF a transfer still conflicts after 10 attempts, THEN THE ledger SHALL roll back and
  return `REJECTED_CONTENTION`, storing nothing for that id, and THE engine SHALL release the id's
  idempotency claim (as for `QUEUE_FULL`), so a retry with the same id is processed fresh.
- REQ-DB-005 WHEN the same transfer id is applied again, including concurrently, from a second
  `PostgresLedger` instance on the same database, or after a restart, THE ledger SHALL apply it at
  most once and return the original outcome (a unique key on the transfer id). This includes
  `REJECTED_FUNDS`: same id, same answer; a new attempt needs a new id (the standard
  idempotency-key rule). Only outcomes that store nothing (`QUEUE_FULL`, `REJECTED_CONTENTION`)
  leave the id free.
- REQ-DB-006 WHEN a transfer is posted, THE posting and its outbox row SHALL commit in the same
  transaction: neither exists without the other.
- REQ-DB-007 THE outbox relay SHALL claim rows with `FOR UPDATE SKIP LOCKED`, publish them to the
  general ledger port, and delete them only after the GL accepts; two relays running at once
  SHALL NOT claim the same row while its lease is live.
- REQ-DB-008 WHEN the engine is drained, including after it was stopped mid-run and a new instance
  started on the same database, every posting SHALL appear in the GL exactly once (REQ-LAB-012).
- REQ-DB-009 THE ledger SHALL make no network call other than to the database inside a
  transaction, and SHALL bound lock waits with `lock_timeout` and statements with
  `statement_timeout`.
- REQ-DB-010 `snapshot()` SHALL return balances whose total equals the opening total
  (REQ-LAB-005), read in a single statement.
- REQ-DB-011 THE schema SHALL change only through Flyway migrations; an applied migration is never
  edited.
- REQ-DB-012 THE database tests SHALL run locally with Docker and on the Ubuntu CI jobs; the Windows
  jobs SHALL run every other test. WHEN database tests are enabled and Docker is missing, THEN they
  SHALL fail, not skip.
- REQ-API-008 WHEN the engine answers `REJECTED_CONTENTION`, THE API SHALL respond 503 with
  `Retry-After`, like `QUEUE_FULL` (REQ-API-001).

## 16. Design

### 16.1 Module

| Module | Contents | Depends on |
| ------ | -------- | ---------- |
| `ledger-postgres` (new) | `PostgresLedger`, `LockMode` (`PESSIMISTIC`, `OPTIMISTIC`), `OutboxRelay`, Flyway migrations | `engine`; `java.sql`/`javax.sql`; PostgreSQL JDBC driver and Flyway (runtime); Testcontainers and HikariCP (test) |

- `PostgresLedger` takes a `javax.sql.DataSource`, so the pool is the caller's choice (HikariCP in
  tests and the runner; Spring Boot's in the api later). Plain JDBC, no JPA: every statement and
  lock is visible.
- The engine's property and stress suites move behind an engine **test-jar**, so `ledger-postgres`
  adds `POSTGRES_PESSIMISTIC` and `POSTGRES_OPTIMISTIC` to the same parameterised suites instead
  of copying them. The module stays on JUnit 5.14 (jqwik).
- ArchUnit: the engine still imports no JDBC; `ledger-postgres` depends on `engine`, never the
  reverse.

### 16.2 Schema (Flyway `V1__ledger.sql`)

| Table | Columns | Why |
| ----- | ------- | --- |
| `account` | `id BIGINT PK`, `balance BIGINT NOT NULL CHECK (balance >= 0)`, `version BIGINT NOT NULL` | balances; `version` for optimistic mode |
| `transfer` | `id TEXT PK`, `outcome TEXT NOT NULL`, `posting_seq BIGINT NULL` | one row per transfer id, every outcome: the idempotency record |
| `posting` | `seq BIGINT PK` (identity), `transfer_id TEXT NOT NULL UNIQUE REFERENCES transfer`, `source`, `target`, `amount` | the ledger's postings, in sequence order |
| `outbox` | `posting_seq BIGINT PK REFERENCES posting`, `attempts INT`, `next_attempt_at TIMESTAMPTZ` | postings not yet accepted by the GL |

### 16.3 One transfer (pessimistic, `READ COMMITTED`)

1. `BEGIN`; `INSERT INTO transfer (id, outcome) VALUES (?, 'PENDING') ON CONFLICT (id) DO
   NOTHING`. Zero rows means a duplicate: read and return the stored outcome. On a conflicting key
   PostgreSQL waits for the other transaction to commit, so concurrent duplicates serialise here,
   and no one ever sees `PENDING`: the row is visible only after step 3 has set the real outcome.
2. `SELECT … FROM account WHERE id IN (?, ?) ORDER BY id FOR UPDATE`: both rows, lower id first.
3. Check funds; update both balances; insert `posting` and `outbox`; set the transfer's outcome.
4. `COMMIT`. On `REJECTED_FUNDS`, step 3 records only the outcome.

Optimistic mode replaces step 2 with a plain read of `balance, version` and makes each update
conditional on the version. A conflict rolls back the whole transaction, the `transfer` row
included, and retries with jittered back-off, at most 10 attempts; after that the answer is
`REJECTED_CONTENTION` and nothing is stored, so the id stays free. `Outcome` gains
`REJECTED_CONTENTION`, and the engine's worker releases the claim for it, as it already does for
`QUEUE_FULL`. `READ COMMITTED` is enough in both modes: the row lock or the
version check does the work, so no `SERIALIZABLE` retry handling is needed. A worker holds one
connection per transfer, so the pool size must be at least the worker count.

### 16.4 Outbox relay

The engine runs with a no-op `PostingPublisher` in database mode, because the outbox row is
already durable. `OutboxRelay` polls `SELECT … FROM outbox WHERE next_attempt_at <= now() ORDER BY
posting_seq LIMIT n FOR UPDATE SKIP LOCKED`, publishes through `ResilientPublisher`, deletes the rows the GL accepted and
pushes back `next_attempt_at` for the rest, all without holding a transaction open during the
GL call: claim with a short lease (`next_attempt_at = now() + lease`), commit, call, then delete.
The GL is keyed by transfer id, so a resend after a lost acknowledgement is harmless.

### 16.5 Tests and CI

- Testcontainers `postgres` image pinned by version; one container per test class, schema reset
  between tests by Flyway `clean` + `migrate`.
- Tagged `database`. A Maven profile `db` (on in the Ubuntu CI jobs and opt-in locally) runs them;
  without it they are excluded, and the module's JaCoCo gate is skipped with them, so Windows CI
  stays green without silently passing an empty check.
- Two-instance test: two `PostgresLedger`s on one database, same transfers from both, invariants
  hold and no id is posted twice (the "two servers" question).
- Restart test: stop the engine with rows in the outbox, start a new one, drain, REQ-DB-008 holds.

## 17. Tasks (v3, test-first)

- [x] V3-01 `ledger-postgres` module, Flyway V1 schema, Testcontainers base test, `db` profile, CI on Ubuntu only; ADR-0001 entries (REQ-DB-002, 011, 012)
- [x] V3-02 Engine test-jar: ledger property and stress suites shared, in-memory ledgers still green (no behaviour change)
- [x] V3-03 `PostgresLedger` pessimistic on the shared suites, opposing-transfer deadlock test, timeouts (REQ-DB-001, 003, 009, 010)
- [x] V3-04 Idempotency by `transfer` key: concurrent duplicates, two instances, after restart, a duplicate `REJECTED_FUNDS` returns the stored rejection; README Decisions entry on the idempotency-key rule (REQ-DB-005)
- [x] V3-05 Optimistic mode on the same suites, retry with jitter capped at 10, `REJECTED_CONTENTION` in `Outcome` with the engine releasing the claim, the api's 503 + `Retry-After` mapping and its test, conflict count in metrics (REQ-DB-004, 004a, REQ-API-008)
- [x] V3-06 Transactional outbox and `OutboxRelay` with `SKIP LOCKED`, two relays, restart and fault drains (REQ-DB-006 to 008)
- [x] V3-07 Runner cells for both database modes behind a `--db` option (local only, not in CI; the database tests still run on Ubuntu CI), README Decisions and results, `docs/learn` notes with Oracle equivalents, one TODO-ME per task

Each task follows the same flow: `test-engineer` writes the failing tests, `database-engineer` or
`java-engineer` makes them pass, `mvnw verify`, `tutor` writes the `docs/learn` note with Oracle
equivalents, then `/ship` (`git-ops` merges on the required green CI checks).

---

# Settlement Lab · v2.1 spec (runs API for the `/lab` page)

Written 2026-11-23 for the portfolio `/lab` page (`docs/explainer/ui-mockup.svg`,
`docs/explainer/ui-sequence.svg`); the wire format is in `docs/api-contract.md`. Same rules as
section 0. Deadline: live demo by 1 Dec 2026. **Status: approved 2026-11-23, amended 2026-11-24 (owner-approved).**

## 18. Runs API

### 18.1 Scope

In: a visitor starts one experiment run (ledger, threads, faults, size, duplicate rate), watches live
metrics over server-sent events, and sees the invariant checks at the end · per-IP and global limits ·
CORS for the portfolio origins · a fresh in-memory engine per run, so a visitor never costs a database
call.

Out: accounts or login, saved run history beyond a short in-memory window, the PostgreSQL ledger in
runs (v3 stays a local and CI experiment), custom workloads beyond the fields below.

### 18.2 Requirements (EARS)

- REQ-RUN-001 WHEN a client POSTs a valid run request to `/api/v1/runs`, THE API SHALL start the run
  asynchronously and answer `202 Accepted` with a `runId`, a `Location` header and the events URL.
- REQ-RUN-002 THE run request SHALL take `ledger` (`LOCKING` | `CAS`), `threads` (`PLATFORM` |
  `VIRTUAL`), `faults` (`NONE` | `FLAKY` | `OUTAGE`), `transfers` (1 to 20,000) and
  `duplicatePercent` (0 to 10); IF a field is missing, unknown or out of range, or an unknown field is
  sent, THEN THE API SHALL answer 400 with an RFC 9457 problem detail and start nothing.
- REQ-RUN-003 WHEN a client opens `GET /api/v1/runs/{id}/events`, THE API SHALL stream
  `metrics` events every 250 ms while the run is active (drain included), then exactly one `report`
  event with the invariant results, then close the stream. Events carry no `id` and there is no
  `Last-Event-ID` replay. IF the run is unknown or expired, THEN 404.
- REQ-RUN-004 WHEN a client connects to the events of a run that has already finished, THE API SHALL
  send the final `report` at once and close.
- REQ-RUN-005 THE API SHALL stop a run after 20 s of wall time; a stopped run drains, checks its
  invariants and reports `"stoppedEarly": true` with the transfers it completed. The report SHALL be
  ready within 60 s of the 202 (OUTAGE included). All deadlines use wall time. IF a run passes 60 s
  without a report (for example, the instance lost its CPU), THEN the next runs request SHALL expire
  it: close its engine and streams, release its slot and answer 404 for it. A run's status is
  `RUNNING` or `DONE` only; after a restart every earlier id answers 404.
- REQ-RUN-006 IF one client IP has started 5 runs in the last hour, THEN THE API SHALL answer 429 with
  `Retry-After` (seconds until the oldest of those runs leaves the window). The client IP is the first
  address, walking `X-Forwarded-For` from the right, outside `settlement.runs.trusted-proxy-ranges`
  (default empty, so the last entry); an entry that is not an IP address, or no entry left, means the
  remote address. IPv6 is keyed by its /64. Client IPs SHALL be held in memory only for the rate-limit
  window, and SHALL never be logged or stored.
- REQ-RUN-007 IF 2 runs are already active, THEN THE API SHALL answer 503 with `Retry-After: 5`; IF
  the global cap of 60 runs per hour is reached, THEN 503 with `Retry-After` until the next slot. Every
  429 and 503 problem body SHALL carry `retryAfterSeconds`, equal to `Retry-After`.
- REQ-RUN-008 THE API SHALL allow cross-origin requests only from `https://siddheshfulawade.com` and
  the Cloudflare Pages preview origin configured in `settlement.runs.preview-origin`; no other origin
  gets CORS headers. This covers the runs endpoints, the event stream, `/actuator/health` and every
  error body.
- REQ-RUN-009 EACH run SHALL use a new in-memory engine (ledger, worker pool, fake general ledger,
  resilient publisher, outbox) with a fixed workload of 1,000 accounts and a fixed seed per run, SHALL
  submit at a steady 2,000 transfers/s (`settlement.runs.transfers-per-second`), and SHALL close it when the report is sent; no state is shared between runs.
- REQ-RUN-010 THE `report` SHALL list each invariant (`TOTAL_CONSERVED`, `NO_NEGATIVE_BALANCE`,
  `POSTED_AT_MOST_ONCE`, `POSTINGS_EXPLAIN_BALANCES`, `POSTINGS_MATCH_ACCEPTED`,
  `GL_MATCHES_POSTINGS`) as passed or failed with its first counterexample (REQ-LAB-014), plus the
  opening and closing totals (minor units) and the duplicates sent and applied. A counterexample is a
  string of at most 500 characters, or null. `durationMs` is the time from the 202 to the end of the
  drain. `throughputPerSecond` is the paced rate, not engine capacity.
- REQ-RUN-011 A slow or disconnected events client SHALL NOT slow or stop the run (as REQ-API-005);
  at most 2 event subscribers per run, a third gets 429. A dropped subscriber's place SHALL be free on
  the next failed write, within 1 s.
- REQ-RUN-012 THE API SHALL keep a finished run's report for 10 minutes, then forget it. A `runId`
  matches `[A-Za-z0-9-]{1,64}`.
- REQ-RUN-013 THE API SHALL expose only `health` of the Spring Boot actuator, without details and
  independent of any database.
- REQ-API-009 IF a transfer id does not match `[A-Za-z0-9._-]{1,64}`, or a body has an unknown field,
  THEN THE API SHALL answer 400; IF a request body is over 16 KB, THEN 413; each as a problem detail,
  submitting nothing.
- REQ-API-010 WHILE the `prod` profile is active, THE API SHALL serve only `/api/v1/runs/**` and
  `/actuator/health`; every other endpoint SHALL answer 404.

### 18.3 Design

- `RunController` (`POST /api/v1/runs`, `GET /api/v1/runs/{id}`, `GET /api/v1/runs/{id}/events`)
  in `api`; `RunService` owns the run registry, the limits and the 20 s deadline; `Run` wraps one
  engine and its workload driver.
- Each run builds its engine through the same factory `EngineConfiguration` uses, with the request's
  ledger, threads and faults; 8 workers, queue capacity 1,024. The driver submits the workload's
  transfers at the paced rate (duplicates re-send an earlier id, as in the runner's `Workload`), then
  drains. 20,000 transfers take 10 s at 2,000/s, inside the 20 s deadline; capacity numbers stay in the
  README benchmarks. The drain is bounded so that 20 s + drain stays under 60 s.
- OUTAGE in a run refuses everything from 1 s to 4 s after the start, so runs under 2,000 transfers
  end their submissions before it begins (the app's 30 s default start would never be reached).
- A sampler on a scheduled executor reads `EngineMetrics`, the publisher's retry and breaker counters
  and the postings waiting for the general ledger (waiting for a sender, being sent, or in the outbox)
  every 250 ms and pushes one `metrics` event per subscriber through a bounded buffer (the V2-04 per-subscriber buffer, reused); a full buffer drops the oldest `metrics` event,
  never the `report`.
- Limits: a per-IP sliding window (5 per hour) and a global window (60 per hour), plus a semaphore of
  2 active runs, all in memory. One instance only (Cloud Run max instances 1, ADR-0002), so in-memory
  limits are exact. The client IP is never a client-supplied first `X-Forwarded-For` entry
  (REQ-RUN-006). The trusted proxy ranges (Cloudflare's) are set and verified at V2-06, with how the
  instance's CPU behaves between requests. The global cap bounds cost even if the per-IP key were
  spoofed. `GET` and health stay outside Cloudflare's rate rule.
- Cost bound: 2 runs × 20 s × 8 workers on one 512 MiB instance; the existing transfer, account,
  metrics and events endpoints stay for local use.
- Engine module unchanged except, if needed, a read-only accessor for retry and breaker counts
  (no Spring in the engine).

### 18.4 Tasks (v2, test-first)

Order: V2-04 (section 13; its bounded per-subscriber buffer is the building block for run events;
reuse `feature/day-7` by merging `origin/main` into it) → V2-07 below → V2-05 (README v2 and an HTTP
load check that includes a run) → V2-06 (deploy, only with the user's yes).

- [x] V2-07 Runs API: request validation, run registry, 20 s deadline, metrics sampler, `report`, limits (429/503 + `Retry-After`), CORS, report retention; `docs/api-contract.md` kept in sync (REQ-RUN-001 to 012)
