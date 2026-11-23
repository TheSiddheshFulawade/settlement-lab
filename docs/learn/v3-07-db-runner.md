# v3-07 · Database cells in the runner (V3-07)

## What it is, in plain words

The runner (`ScenarioRunner`) already compared the in-memory ledgers (LOCKING, CAS) across thread
modes and fault profiles. V3-07 lets it run the two PostgreSQL ledgers (pessimistic and optimistic)
through the same loop: same workload, same clients, same invariant checks, same results row. The hard
part is not the benchmark. It is wiring the database in without teaching the engine about JDBC.

## Part 1: why the engine stays JDBC-free, and how the dependency is inverted

The engine module has ArchUnit rules about which package may import what (`engine/.../ArchitectureTest.java:41-65`,
for example `pipelineImportsOnlyDomainIdempotencyAndJava`, `noSpringAnywhere`). `ledger-postgres`
depends on the engine, never the other way (its own rule: `postgres...DependsOnlyOnJdbcEngineAndFlyway`,
`ledger-postgres/.../ArchitectureTest.java:24`). If `ScenarioRunner` did `new PostgresLedger(...)`, the
engine would need the postgres module on its classpath: a cycle, and a JDBC driver in a library that
promises `java.*` only.

The fix is **dependency inversion**: the engine owns a small interface, the outer module implements it.

```
   engine (runner package)                       ledger-postgres (test sources)
   -----------------------                       ------------------------------
   interface LedgerFactory  <------implements--- PostgresLedgerFactory
   interface GlDelivery     <------implements--- anonymous class inside deliveryTo(...)
   ScenarioRunner.run(factory, ...)  calls the interfaces only
```

- `LedgerFactory` (`LedgerFactory.java:14`): `name()` for the results row, `create(opening)` returning a
  fresh `Ledger` with exactly the opening balances, and `deliveryTo(gl)` which defaults to
  `Optional.empty()` (`:26`). The javadoc at `:10-13` states the reason: the engine must not import JDBC.
- `GlDelivery` (`GlDelivery.java:9`): `drain(timeout)`, `outboxPeak()`, `close()` (no checked exception).
- `ScenarioRunner.run(Scenario)` (`:36`) now just wraps the in-memory `LedgerType` in a factory and calls
  the general overload (`:53`). So there is one code path for every cell, not a copy for the database.

`PostgresLedgerFactory` (`:22`) lives in **test** sources of `ledger-postgres`, so Testcontainers and
Hikari stay test-scoped and the shipped jar has no benchmark code. `create` (`:38`) resets the tables to the
opening balances (`LedgerSeed.reset`) and returns `new PostgresLedger(dataSource, mode)`; the name is
`POSTGRES_PESSIMISTIC` or `POSTGRES_OPTIMISTIC` (`:34`).

## Part 2: in-memory outbox versus database outbox in the runner

Two ways a posting reaches the general ledger (GL), and the runner must pick exactly one, or the GL
sees every posting twice.

```
 no delivery (in-memory cells)          delivery present (database cells)
 -----------------------------          ---------------------------------
 engine publisher = OutboxPublisher     engine publisher = PostingPublisher.NONE
 ledger changes -> publish in memory    posting + outbox row commit together
 outbox.drain(), outbox.outboxPeak()    delivery.drain(), delivery.outboxPeak()
```

The choice is at `ScenarioRunner.java:80`: `delivery.isPresent() ? PostingPublisher.NONE : outbox`. Drain and
peak follow the same split (`:86-88`). The database already holds the message durably (V3-06), so the engine
publishing it too would be a second, in-memory copy. The comment at `:73-74` says so.

`PostgresLedgerFactory.deliveryTo` (`:44`) builds an `OutboxRelay` (batch 100, 5 s lease), starts one
platform thread named `outbox-relay` that loops `relayOnce()` and parks 20 ms when it moved nothing
(`:48-55`), and records the largest outbox row count it saw (`peak`). `drain` calls `relay.drain(timeout)`;
`close` stops the loop and joins the thread (`:68-74`). The runner closes it in a `finally` (`:115-118`),
with the invariant written there: background threads are closed on every path.

Note the order: after the engine drains, the runner waits for the GL to catch up. If it does not, the
row fails with "general ledger did not catch up before the drain timeout" (`:95`), so a stuck relay
cannot pass silently.

Also `rejectedContention` is added into the Rejected column (`:104-108`): a contention refusal stores
nothing, like queue-full.

## Part 3: why the cells run locally and not in CI, while DB correctness tests do run in CI

Two different jobs:

- **Correctness** (`DbScenarioRunTest`): a tiny cell per lock mode with and without faults (50 accounts,
  500 transfers, `RunnerSettings.quick()`, `:31-32`). It asserts every invariant passes, duplicates occurred, the
  outbox table ends empty and postings exist. It is fast, and CI runs it: the workflow adds `-Pdb` on the Linux
  legs (`.github/workflows/verify.yml:57`), because the database tests are tagged `database` and excluded
  by default (`PostgresTestBase.java:18`).
- **Measurement** (`DbResultsMain`): 2 lock modes x 2 thread modes x 3 fault profiles = 12 cells of 20,000
  transfers each (`:61-63`), on a container database. Not in CI because of:
  - **time**: it is minutes of wall clock, on every one of four matrix legs;
  - **noise**: shared runners have unknown neighbours and unpredictable CPU and disk, so a number from them
    cannot be compared with another run;
  - **no point measuring on a different machine each time** (CI also adds `-Pdb` only on the Linux legs).

A benchmark that varies with the runner is worse than none, because it looks like evidence.

## Part 4: how to run it, and why the test classpath

```
mvnw.cmd -Pdb -pl ledger-postgres -am test-compile exec:java -Dexec.classpathScope=test -Dexec.mainClass=com.siddheshfulawade.settlementlab.postgres.DbResultsMain
```

- `-Pdb` turns on the database profile.
- `-pl ledger-postgres -am` builds that module and what it needs (the engine).
- `test-compile` compiles the test sources, because `DbResultsMain` and `PostgresLedgerFactory` live there.
- `-Dexec.classpathScope=test` is the key: by default `exec:java` uses the compile classpath, which has
  no Testcontainers, Hikari or test classes, so the class would not be found.
- Needs Docker running: `DbResultsMain` starts a PostgreSQL container itself (`:52`).
- Options (`:22-26`): `--accounts`, `--transfers`, `--hot-accounts`, `--hot-percent`, `--duplicate-percent`,
  `--seed`, `--out`. Defaults: 1000 accounts, 20,000 transfers, 20 hot accounts, 20 % hot, 2 % duplicates,
  seed 20261119. Output goes to `results/results-db.md` and `.json` (`DbResultsFiles.java:20-27`),
  separate from the committed in-memory results. Exit code 1 if any invariant failed (`:74-76`).
- The pool is `max(16, workers + 4)` (`:58`).

## Part 5: what to expect, pessimistic versus optimistic under hot accounts

These are expectations to check, not results. No database run is committed yet (Docker was unavailable).

- **Pessimistic** locks the account rows (in a fixed order, from V3-03), so two transfers on the same hot
  account **wait** in line. Cost shows as latency (p99 grows), not as failed work. A wait that exceeds
  `lock_timeout` aborts with SQLState 55P03 (`PostgresLedger.java:145-153`), a retryable error.
- **Optimistic** does not lock while reading. It writes with a version check, and the loser sees 0 rows
  updated, **conflicts**, backs off with jitter and **retries**, at most 10 times, then answers
  `REJECTED_CONTENTION` (`PostgresLedger.java:63`, `:253`; `LockMode.java:12`). Cost shows as wasted work and
  as rejections.
- With few hot accounts or low `--hot-percent`, optimistic usually wins or ties (no lock round-trips,
  almost no conflicts). As the hot share rises, conflicts and retries climb and rejections appear; pessimistic
  degrades more smoothly. The exact crossover depends on the machine, so find it by running, not by
  asserting. `PostgresLedger.conflicts()` (`:382`) is the counter; the TODO-ME below uses it.

## Part 6: platform versus virtual threads with a JDBC pool

A virtual thread that blocks on JDBC is cheap to park, but it still needs a **connection** to do work.
The pool is a hard cap on concurrent database work: `max(16, workers + 4)`. Eight workers (the default) never
need more than 8 connections plus the relay, so threads are not the limit; the pool, and below it the
database's own locking and disk, are. Making threads cheaper cannot raise a limit set by connections. Expect
platform and virtual cells to be close, as they were for the in-memory runs; a difference would more likely
show in p99 than throughput.

Pinning: on **Java 21 to 23**, a virtual thread blocked inside a `synchronized` block **pins** its carrier
thread (the OS thread under it), so the carrier cannot run others. If a JDBC driver or pool uses
`synchronized` around blocking I/O, many virtual threads can exhaust the few carriers. Java 24 (JEP 491)
fixed the `synchronized` case; native frames can still pin. This is one reason the project benchmarks on
Java 25 (CLAUDE.md), while the code compiles on 21. Mention it as a possible explanation if the 21 and 25
runs differ; do not claim it without measuring (`-Djdk.tracePinnedThreads` on 21 shows it).

## Part 7: benchmark hygiene

- **Warm-up**: the first cells pay for class loading and JIT compilation and a cold buffer cache. This runner
  has no warm-up pass, so the first cell is probably unfairly slow; say that, or throw away a first lap.
- **Same seed**: every cell uses the same `Workload` seed (`DbResultsMain.java:42`), so the transfers are
  identical and a difference comes from the ledger, not the data.
- **One run is not a benchmark**: one run has no spread. Repeat, compare medians, and report the machine,
  JDK and Docker/PostgreSQL version. The README already says "a single run, never from a production system".
- **Fresh state**: `create` resets the tables for each cell, so one cell does not inherit the previous
  one's rows or bloat.
- **A container is not production**: local disk and loopback network make lock waits look different from a
  remote database such as Neon in another region.

## Oracle equivalents

| Here (PostgreSQL) | Oracle |
| --- | --- |
| Lock waits seen as high p99 under pessimistic | **AWR** report (Top Wait Events, `enq: TX - row lock contention`) and **ASH** (`v$active_session_history`, `blocking_session`) for who waited on whom |
| Look at current locks | `v$lock`, `v$session` (`blocking_session`), `dba_waiters` |
| Serialization failure, SQLState 40001, retry | **ORA-08177** "can't serialize access for this transaction" under `SERIALIZABLE`; also ORA-00060 for deadlock. Retry the whole transaction |
| Version-check optimistic update (`... WHERE version = ?`, 0 rows = conflict) | the same with a **version column**, or `ORA_ROWSCN` (row SCN; accurate only if the table is created with `ROWDEPENDENCIES`, otherwise it is per block and gives false conflicts). A version column is the dependable choice |
| `lock_timeout` | `SELECT ... FOR UPDATE WAIT n` (or `NOWAIT`) |
| Pool is the limit | UCP/Hikari pool size versus the database's `processes`/`sessions`; many sessions on few cores hurt on Oracle too |

## Interview questions

1. **Why does the engine define `LedgerFactory` instead of the runner creating the PostgreSQL ledger?** The
   engine must not depend on JDBC, and the postgres module already depends on the engine, so the engine
   importing it would be a cycle. The engine owns an interface and the database module implements it
   (dependency inversion), so the runner works with any ledger and ArchUnit keeps the rule enforced.
2. **In database mode why does the engine get `PostingPublisher.NONE`?** The outbox row is written in the same
   transaction as the posting and the relay delivers it. A second in-memory publish would send every posting
   twice (harmless because the GL is keyed by transfer id, but wasted and it muddies the benchmark). So the
   runner chooses exactly one delivery path (`ScenarioRunner.java:80`).
3. **Why are the database benchmark cells not run in CI, but the database tests are?** Tests prove
   correctness and are small and deterministic enough to run on any machine. Benchmarks are only meaningful on
   a stable machine; shared runners add noise and the matrix takes minutes on four legs. A noisy benchmark
   gives false confidence, so it runs locally and the machine is written next to the numbers.
4. **Under a hot account, how do pessimistic and optimistic fail differently?** Pessimistic queues: callers
   wait on the row lock, so latency grows and a long wait can hit `lock_timeout`. Optimistic never waits but
   collides: the version check fails, the work is redone with jittered back-off, and after 10 attempts the
   answer is `REJECTED_CONTENTION`. So one pays in waiting, the other in wasted retries and refusals.
5. **Will virtual threads make the database cells faster?** Probably not by much. Virtual threads make blocking
   cheap, but each unit of work still needs a pooled connection, and the pool, the locks and the disk are the
   limit. They could change tail latency. On Java 21 to 23, `synchronized` around blocking I/O can pin the
   carrier thread; Java 24 removed that case. I would measure, repeat and report before claiming a win.

## What breaks if... (exercise)

In `ScenarioRunner.run`, change `delivery.isPresent() ? PostingPublisher.NONE : outbox` so the engine always
gets `outbox`. Predict first: do invariants still pass? What happens to the GL entry count, to
`resilient.retries()`, and to the outbox peak? Then run `DbScenarioRunTest`. Which assertion, if any, fails,
and why is "still correct but doubled work" harder to catch than a failure?

## TODO-ME

`DbHotAccountSkewTest.REQ_DB_013_optimistic_conflicts_grow_with_the_hot_percent` (line 13) is yours, currently
`@Disabled` with an empty body. Run an `OPTIMISTIC` cell through `ScenarioRunner` (copy the shape of
`DbScenarioRunTest`) at `hotPercent` 0 and at 90 on the same seed, read `PostgresLedger.conflicts()` from each
ledger (you will have to keep a reference in `PostgresLedgerFactory.create`), and assert conflicts at 90 are
greater than at 0. Check: it passes; then make the two percents equal and watch it fail. Remove the
`@Disabled`.
