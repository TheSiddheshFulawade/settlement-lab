# v3-03 · PostgreSQL pessimistic ledger (V3-03)

## What it is, in plain words

`PostgresLedger` (`ledger-postgres/.../PostgresLedger.java`) is the same `Ledger` the engine already
knows, but the balances, transfers and postings live in PostgreSQL. "Pessimistic" means: assume two
transfers will fight over an account, so lock the account rows first and let the loser wait. It uses
plain JDBC, so every statement is visible. `LockMode.PESSIMISTIC` is the only mode that works now;
`OPTIMISTIC` throws `UnsupportedOperationException` (`PostgresLedger.java:100`) until V3-05.

## One transfer, step by step (`applyWithResult`, lines 126-164; SPEC 16.3)

Before the database: amount and same-account rules are checked in Java (line 128). Then ONE
transaction on ONE connection:

```
0. SET TRANSACTION ISOLATION LEVEL READ COMMITTED;
   SET LOCAL lock_timeout = '5000ms'; SET LOCAL statement_timeout = '30000ms'     (lines 103-105)
1. INSERT INTO transfer (id, outcome) VALUES (?, 'PENDING') ON CONFLICT (id) DO NOTHING   (line 53)
     0 rows inserted -> duplicate: read and return the stored outcome, done.
2. SELECT id, balance FROM account WHERE id IN (?, ?) ORDER BY id FOR UPDATE            (line 60)
     fewer than 2 rows -> unknown account: ROLLBACK (the claim goes too), REJECTED_INVALID.
3. balance of source < amount?  -> UPDATE transfer SET outcome='REJECTED_FUNDS'; COMMIT.
   else: UPDATE account SET balance = balance + ? WHERE id = ?   (twice, one batch, line 64)
         INSERT INTO posting ... RETURNING seq                   (line 67)
         UPDATE transfer SET outcome='POSTED', posting_seq=?     (line 69)
4. COMMIT  (locks released)
```

In words: claim the id, lock both accounts, check funds against the locked balance, move the money,
write the posting, record the outcome, commit. Everything happens or nothing does.

```
 T1: A->B                       T2: B->A
 lock A (row 1)  ------------>  wants A first too (ascending order): waits
 lock B (row 2)                 ...
 update, COMMIT  ------------>  gets A, then B, proceeds
```

## Why `ORDER BY id FOR UPDATE` prevents deadlock

A deadlock is a cycle: T1 holds a row T2 wants, and T2 holds a row T1 wants. If every transaction
takes its rows in ascending id order, a transaction holding row k only ever waits for rows above k
(it already has everything below). Waits always point upward, so they can never loop back. Opposing
transfers A->B and B->A both lock A first (`PostgresLedger.java:138-141`). Note the source/target
order is NOT the lock order: `REQ_DB_003_the_lower_account_id_is_locked_first_even_when_it_is_the_target`
(`PostgresLedgerLockingTest.java:109`) proves a 2->1 transfer waits on row 1 without holding row 2.
PostgreSQL sorts first, then locks (the locking step runs on the ORDER BY output), which is what the
comment on line 57 relies on.

## Why READ COMMITTED is enough

Each statement sees the data committed when it started. Normally that allows a lost update (read 100,
someone else writes 70, you write 80). Here it cannot happen: the balance we use comes from
`SELECT ... FOR UPDATE`. If the row is locked by someone else, we wait, and after they commit,
`FOR UPDATE` re-reads the latest committed row. We then hold the lock until COMMIT, so nobody changes
it between our check and our update. SERIALIZABLE would only add `40001` retry errors for no extra
safety. The isolation level is set explicitly (line 103) so a pool configured differently cannot
change behaviour.

## Timeouts and `SET LOCAL`

- `lock_timeout`: longest one statement waits for a row lock. Fires with SQLState `55P03`.
- `statement_timeout`: longest one statement runs, lock waits included. SQLState `57014`.
- Why: without them, a stuck holder would pin every worker forever (REQ-DB-009). Defaults 5 s / 30 s
  (lines 48, 50); values under 1 ms are refused because `0` in PostgreSQL means "no limit" (line 112).
- `SET LOCAL` lasts only until COMMIT or ROLLBACK, so the limit never stays on a pooled connection for
  the next borrower. `REQ_DB_009_the_timeouts_do_not_stay_on_the_pooled_connection` checks it
  (`PostgresLedgerLockingTest.java:191`). Plain `SET` would leak.
- On timeout the transaction is rolled back (the claim row too) and `LedgerDatabaseException` is
  thrown (unchecked, cause is the `SQLException`). It is not an `Outcome`, because the transfer was
  neither accepted nor rejected: the same id can simply be retried
  (`PostgresLedgerLockingTest.java:165`).

## Why `snapshot()` is one statement (lines 271-289)

In READ COMMITTED each statement sees one consistent set of committed data, and a transfer commits
both balance changes at once. So one `SELECT id, balance FROM account ORDER BY id` never shows half a
transfer, and the total equals the opening total. Two statements could straddle a commit and see the
money gone (or doubled). No locks are taken, so unlike `LockingLedger` it pauses no transfer.

## Connection per transfer and pool size (lines 38-41, 322)

`inTransaction` borrows one connection, runs the work, commits or rolls back, and returns it.
No call ever holds one connection while asking for a second: with a small pool that is how every
worker ends up waiting for a connection that only another waiting worker could free. So the pool needs
at least one connection per worker (tests use 16, `PostgresTestBase.java:47`). The pool restores
autocommit when the connection comes back.

## The CHECK constraint, last line of defence

`CHECK (balance >= 0)` (V3-01). Step 3 already checks funds, but if that check were ever wrong, the
`UPDATE` fails with `23514`, the whole transfer rolls back, and no negative balance is stored
(comment at `PostgresLedger.java:157`).

## Oracle equivalents

| PostgreSQL here | Oracle |
| --- | --- |
| `ORDER BY id FOR UPDATE` locks in id order | Not guaranteed: Oracle may lock rows as it produces them, not in ORDER BY order. Lock each row with its own `SELECT ... FOR UPDATE WAIT n`, lower id first |
| `SET LOCAL lock_timeout` | `FOR UPDATE WAIT n` (seconds) or `NOWAIT` |
| `SET LOCAL statement_timeout` | JDBC `setQueryTimeout` (or Resource Manager limits) |
| duplicate key `23505`; `ON CONFLICT DO NOTHING` | `ORA-00001`; `MERGE` (or catch the error) |
| READ COMMITTED (we set it) | the default, with the same row-lock behaviour |
| deadlock `40P01` | `ORA-00060` |

## Interview questions

1. **Why lock accounts in ascending id order?** A deadlock needs a cycle of waits. A transaction that
   holds row k only waits for rows above k, so waits always climb and cannot form a cycle. A->B and
   B->A both lock the lower id first.
2. **Why is READ COMMITTED enough with row locks?** `FOR UPDATE` waits for the lock, then re-reads the
   latest committed row, and we hold the lock until commit. So the balance we check is the balance we
   change: no lost update. SERIALIZABLE would only add retries.
3. **What do `lock_timeout` and `statement_timeout` do, and why `SET LOCAL`?** Lock wait limit (55P03)
   and total statement time limit (57014), so a stuck holder cannot block workers forever. `SET LOCAL`
   ends with the transaction, so a pooled connection is not left with our limits.
4. **Why is `snapshot()` a single statement?** One statement sees one committed state, and each
   transfer commits both sides together, so the total is exact. Two reads could straddle a commit.
5. **Why one connection per transfer, and how big is the pool?** Holding one while waiting for another
   can exhaust the pool and stall all workers. The pool needs at least one connection per worker.

## What breaks if... (exercise)

Remove `ORDER BY id` from `LOCK_ACCOUNTS`, or run with autocommit on. Predict first, then try it.
Without ORDER BY, PostgreSQL may lock rows in whatever order it finds them, so opposing transfers can
cross: one holds A and wants B, the other holds B and wants A. PostgreSQL detects it after
`deadlock_timeout` (1 s by default), kills one with `40P01`, and
`REQ_DB_003_opposing_transfers_A_to_B_and_B_to_A_never_deadlock` (`PostgresLedgerLockingTest.java:64`)
should fail. (With only two rows the planner may happen to scan in id order, so the ring test at line
87 or a larger table is a fairer check: try it and see.) With autocommit, the `FOR UPDATE` lock is
released the moment that statement ends, so check-then-update is no longer atomic: lost updates, or a
half-applied transfer (debit without credit). Which invariant tests fail, and why?

## TODO-ME

`PostgresLedgerLockingTest.REQ_DB_009_a_locked_target_row_times_out_too` (line 213) is left for you:
hold the TARGET row (id 2), run 1 -> 2 with a 500 ms lock timeout, and assert `55P03`, account 1 still
100, and the `transfer` table empty. The comment above it says how to check that your test fails when
the lock on the higher id is skipped.
