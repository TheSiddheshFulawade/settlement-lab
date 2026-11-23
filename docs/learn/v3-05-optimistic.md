# v3-05 · Optimistic PostgreSQL ledger (V3-05)

## What it is, in plain words

Two transfers may touch the same account at the same time. Something must stop the second one from
overwriting the first one's change (a "lost update"). There are two ways to do it:

- **Pessimistic ("lock first")**: lock the two account rows before looking at them. Anyone else
  who wants those rows waits in line. (V3-03, `PostgresLedger.java:183`.)
- **Optimistic ("check at the end")**: do not lock. Read the balances, work out the new ones, and
  when writing, say "only change this row if nobody changed it since I read it". If somebody did,
  throw the attempt away and try again. (V3-05, `PostgresLedger.java:229` and `:254`.)

The "did anybody change it?" test is a **version** column on `account` (`V1__ledger.sql:14`, `version
BIGINT NOT NULL DEFAULT 0`). Every successful write adds 1. `LockMode` (`LockMode.java`) picks the
style when you build the ledger: `PESSIMISTIC` or `OPTIMISTIC`.

Think of editing a shared document. Pessimistic: you put a "do not touch" sign on it while you edit.
Optimistic: you edit your own copy, and on save the system says "someone saved since you opened it,
reload and redo your edit".

## One optimistic attempt, step by step

Each attempt is its own short transaction on its own borrowed connection (`applyOptimistic`, line
229). The method is `optimisticAttempt` (line 254).

```
Step 1  INSERT INTO transfer (id, outcome) VALUES (?, 'PENDING') ON CONFLICT (id) DO NOTHING
        0 rows -> the id is already recorded: return the stored answer (same as V3-04)       :258
Step 2  SELECT id, balance, version FROM account WHERE id IN (?, ?)        (no FOR UPDATE)   :263
        fewer than 2 rows -> store REJECTED_INVALID                                           :265
        source balance < amount -> store REJECTED_FUNDS                                       :277
Step 3  UPDATE account SET balance = ?, version = version + 1
         WHERE id = ? AND version = ?                       (once per account, LOWER id FIRST) :293-299
        either statement changes 0 rows -> throw VersionConflict -> ROLLBACK                  :300
Step 4  INSERT INTO posting ... RETURNING seq ; UPDATE transfer SET outcome='POSTED' ...      :303-304
        COMMIT
```

Points to notice:

- The read in step 2 is ONE statement, so both balances come from one snapshot. It takes no row lock
  and never waits for anybody.
- The new balances are computed in Java from what was read (`:293`), not as `balance = balance + ?`.
  That is why the version check is needed: a computed value is only right if the row is unchanged.
- `versionedUpdate` (line 331) returns `executeUpdate() == 1`. Zero means "the version moved".
- A conflict rolls back the WHOLE attempt, the claim row from step 1 included (comment at `:237`). So
  nothing is half done, and a duplicate that was waiting on our claim can now go ahead.
- A rejection (`REJECTED_FUNDS`) writes no account, so it needs no version check. It was correct at
  the instant of the read, and it is stored like in V3-04.
- The `CHECK (balance >= 0)` is still the last line of defence (`:327` comment).

## Why the version check prevents a lost update

Without the check, two transfers both read balance 100 and both write "100 - 30 = 70" (one debit
vanishes). With `AND version = ?` the second writer fails:

```
 Account A: balance 100, version 5. Two transfers T1 and T2 each move 30 out of A.

 T1: READ  A = (100, v5)
 T2: READ  A = (100, v5)
 T1: UPDATE A SET balance=70, version=6 WHERE id=A AND version=5   -> 1 row   (T1 wins)
 T1: COMMIT                                                          A is now (70, v6)
 T2: UPDATE A SET balance=70, version=6 WHERE id=A AND version=5   -> 0 rows  (version is 6)
 T2: sees 0 rows -> VersionConflict -> ROLLBACK -> back-off -> attempt 2
 T2: READ  A = (70, v6)  -> UPDATE ... balance=40 WHERE version=6  -> 1 row   (correct: 40)
```

The stale write changes 0 rows, so the stale number is never stored.

What if T1 has not committed yet when T2's UPDATE runs? Then T2's UPDATE **waits** for T1's row lock
(an UPDATE always locks the row it changes). When T1 ends, T2 does not blindly continue. In READ
COMMITTED PostgreSQL re-checks T2's WHERE clause against the row T1 left behind (comment `:283-287`):

- T1 committed: the row now says version 6, `version = 5` is false, 0 rows changed. Conflict.
- T1 rolled back: the row is still version 5, the WHERE is true, T2's update goes ahead. Correct,
  because T1's change never happened.

## Why ascending order still matters

Optimistic does not mean lock-free. The UPDATEs take row locks (briefly, until COMMIT). If T1 updated
A then B while T2 updated B then A, each would hold one row and wait for the other: a deadlock. So the
two UPDATEs run lower account id first (`:295-299`), like the pessimistic `FOR UPDATE`. A transaction
holding row k only waits for a row above k, so waits only climb and never form a circle. The locks
are held only for the last few statements, not across the read and the thinking, so they are far
shorter than the pessimistic ones.

## Back-off: full jitter

After a conflict the attempt sleeps, then tries again (`backOff`, line 347):

```
ceiling(n) = min(50 ms, 1 ms * 2^(n-1))      n = conflicts so far     (backoffCeiling, line 362)
sleep      = a random time from 0 to ceiling(n)                       (full jitter, line 350)
```

Ceilings after conflict 1, 2, 3, ...: 1, 2, 4, 8, 16, 32, 50, 50, 50 ms. There is no sleep after the
tenth failure, so there are nine back-offs, at most 213 ms in total (the test says under a second:
`PostgresLedgerBackoffTest.java:22`).

Why random? Imagine 100 transfers all hitting the same hot account. They all conflict at the same
moment. If they all wait exactly 4 ms, they all wake at the same moment and all collide again: a
**thundering herd**. A random wait from 0 to the ceiling spreads them out, so only a few collide per
round. Why "full" jitter (0 to ceiling) and not "ceiling plus a little"? It spreads them the most, and
some retry almost at once, which is good when the conflict was a one-off. The cost is that no minimum
delay is promised, which is fine.

Why the sleep sits between transactions: the connection has been handed back and no claim or lock is
held, so a sleeping transfer pins no pool connection and no row (comment `:225-228`).

## Why cap at 10 attempts, then REJECTED_CONTENTION

Retrying forever has two problems. Under heavy contention a transfer may lose again and again, so
the caller waits without bound and holds a worker thread, which starves everyone else. And all those
retries are wasted work that makes the contention worse. So after `MAX_OPTIMISTIC_ATTEMPTS = 10`
(line 64) the ledger answers `REJECTED_CONTENTION` (line 247) and gives the thread back. The caller
learns the truth: "the system is too busy on these accounts, try again later".

It **stores nothing**: every attempt rolled back, so there is no `transfer` row for the id. That is
deliberate (REQ-DB-004a). It is not a decision about the payment, so it must not be remembered, unlike
`REJECTED_FUNDS` which is a real answer. Test: `REQ_DB_004a_after_ten_conflicting_attempts_it_answers_contention_and_stores_nothing`
(`PostgresLedgerOptimisticTest.java:134`) and `..._the_same_id_is_processed_fresh_after_contention` (`:157`).

## The engine releases the claim; the API says 503 + Retry-After

The engine keeps its own in-memory claim per id (`IdempotencyStore`), so duplicates arriving in the
same process get the first caller's future. If `REJECTED_CONTENTION` kept that claim, every retry
with the same id would be told "contention" forever, even though the database stored nothing. So in
`SettlementEngine.process` (line 143-147) the claim is released BEFORE the future is completed, so a
caller who retries instantly is processed fresh. It is counted in `EngineMetrics.rejectedContention`
(line 161 and 249). Tests: `SettlementEngineContentionTest.java:94`, `:115`, `:132` (nothing is
published to the general ledger).

At the API (`TransferResponses.java:22` and `:33-34`) `REJECTED_CONTENTION` maps to **503 Service
Unavailable with `Retry-After: 1`**, the same as `QUEUE_FULL`. Why 503 and not 409 or 500? The request
was valid and the server is just too busy; a 5xx that is "temporary" tells clients and proxies they
may retry. The client retries with the SAME id, which is safe because nothing was stored, and if the
first try did secretly commit, the id returns the stored answer anyway (V3-04).

## When does optimistic win or lose?

- **Low contention** (many accounts, transfers rarely overlap): optimistic wins. No lock waits, no
  lock bookkeeping, conflicts almost never happen, so the retry loop almost never runs.
- **High contention** (a few hot accounts): optimistic loses. Most attempts do their reads and
  computing, then fail and are thrown away, so work is repeated and 10-attempt rejections appear.
  Pessimistic just queues, wastes no work, and never rejects for contention.
- **Where the line is** is a measurement, not an opinion: V3-07 measures both modes at several
  contention levels. Do not quote numbers before then.

## Oracle equivalents

| PostgreSQL here | Oracle |
| --- | --- |
| `version BIGINT` column, `UPDATE ... WHERE id = ? AND version = ?` | the same statement; check the JDBC update count or `SQL%ROWCOUNT` for 0 |
| `ORA_ROWSCN` | Oracle can give a per-row (or per-block) "last change" number. With `ROWDEPENDENCIES` on the table it is per row, and `WHERE ORA_ROWSCN = ?` can replace a version column. Without it, it is per block, so unrelated rows can cause false conflicts. An explicit version column is clearer and portable |
| UPDATE waits for an uncommitted writer, then re-checks the WHERE | Oracle does the same under READ COMMITTED, called **write consistency**: it waits, then if the row changed it restarts the UPDATE statement against the new row. Same result: committed change means 0 rows, rollback means it proceeds |
| `SELECT ... FOR UPDATE` (the pessimistic counterpart, V3-03) | identical syntax, with `WAIT n`, `NOWAIT` or `SKIP LOCKED` options |
| SERIALIZABLE retry on `40001` (we do not use it) | `ORA-08177: can't serialize access for this transaction` is the same idea: the database detects the conflict and the application retries |
| `lock_timeout` for waits | `FOR UPDATE WAIT n`; an UPDATE has no wait clause, so use a JDBC query timeout |
| JPA/Hibernate `@Version` | works on both: Hibernate adds `AND version = ?` to its UPDATE and throws `OptimisticLockException` when 0 rows change. It is this exact pattern with the SQL hidden |

## Interview questions

1. **Explain optimistic versus pessimistic locking, and when you would choose each.** Pessimistic
   locks the rows first so others wait; optimistic reads freely and checks at write time that nothing
   changed, retrying on conflict. Choose optimistic when conflicts are rare (the common case is
   cheaper: no waiting, no locks held while thinking); choose pessimistic when conflicts are frequent
   or the work is expensive to redo. Here: optimistic for many accounts, pessimistic for a few hot
   ones, and V3-07 measures where they cross.
2. **How does `WHERE version = ?` prevent a lost update?** Each write bumps the version. A writer
   who read an older version finds the WHERE false, so the UPDATE changes 0 rows and we know our
   read is stale and nothing was written. We roll back and redo from a fresh read. If the other
   writer is still uncommitted, our UPDATE waits for its row lock and re-checks the WHERE once it
   ends (READ COMMITTED), so the result is the same.
3. **Why still update in ascending id order if there are no read locks?** The UPDATEs do take row
   locks, and two transfers updating the same two rows in opposite order would deadlock. Ordering by
   id means waits only climb, so no cycle can form. Optimistic removes the long locks, not the short
   ones.
4. **What is full jitter and why use it?** Sleep a random time between 0 and an exponentially
   growing ceiling (1 ms doubling to a 50 ms cap). Without randomness, all transfers that collided
   wake together and collide again (thundering herd). Randomness spreads the retries, and the
   doubling reduces pressure as contention proves persistent.
5. **After 10 conflicts you return `REJECTED_CONTENTION` and store nothing. Why, and what should the
   client do?** Unbounded retries hold a worker and add load to an already hot row, so we bound
   them and fail fast. Nothing is stored because it is not a decision about the payment: the id stays
   free and the engine drops its in-memory claim. The API answers 503 with `Retry-After`; the client
   retries with the same id, which is safe because either nothing happened or the stored answer is
   returned.

## What breaks if... (exercise)

Option A: delete `AND version = ?` from `VERSIONED_UPDATE` (line 102), so the WHERE is only `id = ?`.
Predict first, then run `REQ_DB_004_no_lost_update_when_many_threads_hammer_two_hot_accounts`
(`PostgresLedgerOptimisticTest.java:88`) and the conflict tests (`:116`, `:134`). Every UPDATE now
returns 1, so no conflict is ever detected. Two threads that read the same balance both write a value
computed from it, so one debit is lost: money is created, and the total-money invariant fails. The
forced-conflict tests also fail, since they expect a retry or a `REJECTED_CONTENTION` that can no
longer happen. Why is the `CHECK (balance >= 0)` not enough to save you here?

Option B: replace the random sleep (line 350) with `ceilingNanos`. Money stays correct (the version
check is untouched), so the safety tests pass. What gets worse? Think about 100 threads on one hot
pair and what happens to `conflicts()` and to how many end as `REJECTED_CONTENTION`. This is also why
a test for jitter is a different test from a test for correctness.

## TODO-ME

`PostgresLedgerOptimisticTest.two_ledgers_retrying_the_same_conflict_do_not_sleep_identical_delays`
(line 224) is left for you, currently `@Disabled` with an empty body. Build two `PostgresLedger`
objects over the same seeded data, force 10 conflicts on each through `afterReadHook`, record the
time between consecutive hook calls, and assert the two lists of delays are not equal. Use a loose
check, never an exact time (sleeps are imprecise). Check: it passes today; swap the random delay for
a constant and it must fail. Remove the `@Disabled` line.
