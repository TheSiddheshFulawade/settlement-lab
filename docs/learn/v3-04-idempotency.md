# v3-04 · Idempotency by the database key (V3-04)

## What it is, in plain words

A transfer id is a promise: "this is ONE payment instruction". If the same id arrives twice (a client
timeout and retry, a double click, two servers receiving the same message), the money must move at
most once, and the second caller must get the SAME answer as the first. `PostgresLedger` does this
with the primary key of the `transfer` table (`V1__ledger.sql:20`, `id TEXT PRIMARY KEY`). No
separate "have I seen this?" cache exists: the table of transfers IS the memory.

## The rule (REQ-DB-005, SPEC 15 and 16.3)

- Same id, same answer. This includes rejections: a `REJECTED_FUNDS` or `REJECTED_INVALID` (unknown
  account) is stored on the id's row and replayed (`PostgresLedger.java:160`, `:169`, `storedResult`
  at `:193`). Test: `REQ_DB_005_a_duplicate_of_a_rejected_for_funds_transfer_returns_the_stored_rejection_even_if_it_would_now_fit`
  (`PostgresIdempotencyTest.java:141`).
- A new attempt needs a new id. After a rejection for funds, topping up the account and resending
  the same id still returns the rejection; sending `x-again` posts (`:154`).
- Only outcomes that store nothing leave the id free: `QUEUE_FULL` and `REJECTED_CONTENTION`
  (engine level, SPEC REQ-DB-004a), and a rolled-back error such as a lock timeout, which throws
  `LedgerDatabaseException` and rolls the claim back too (`PostgresLedger.java:127-132`; test at
  `PostgresIdempotencyTest.java:188`).
- One more case stores nothing: a non-positive amount or same source and target is rejected in Java
  before any SQL (`:138`). That is safe because the answer depends only on the request itself, so a
  replay gets the same answer anyway (test `:177`).

Why do payment APIs work this way? The caller's real question after a timeout is "did my payment
happen?". A replay that returns the original answer lets the client retry blindly and safely. If a
rejection were forgotten, the same id could be rejected today and posted tomorrow after a top-up: the
client who saw "insufficient funds" and gave up would be surprised by a payment. A stable answer per
id also makes the audit trail simple: one id, one row, one outcome. A genuinely new attempt is a new
decision, so it gets a new id.

## How the unique key serialises concurrent duplicates

Step 1 of every transfer (`CLAIM`, line 58; `claim`, line 181):

```
INSERT INTO transfer (id, outcome) VALUES (?, 'PENDING') ON CONFLICT (id) DO NOTHING
  1 row  -> we own this id: carry on with steps 2-4
  0 rows -> someone else owns it: SELECT the stored row and return it (storedResult)
```

Two duplicates at the same moment:

```
 T1: INSERT 'dup'  -> inserts (uncommitted)      T2: INSERT 'dup'  -> same key, T1 not finished:
 T1: lock accounts, move money,                       T2 WAITS (on the key, holding no other lock)
     UPDATE outcome='POSTED'
 T1: COMMIT  ------------------------------------>  T2 wakes: key now committed -> inserts 0 rows
                                                    T2: SELECT stored row (sees T1's commit)
                                                    T2 returns T1's posting. Nothing moved twice.
 (if T1 had ROLLED BACK instead: T2's insert would go ahead and T2 would do the work.)
```

Three details a careful reader checks:

- The wait is built into the unique index: the database cannot decide whether the key exists until
  T1 commits or rolls back, so it blocks T2. We write no lock code for it (comment at line 142).
- READ COMMITTED: each statement sees everything committed before it began. T2's SELECT begins after
  the wait ends, so it sees T1's commit (`storedResult`, line 189 comment).
- `PENDING` is never visible. The row is inserted as `PENDING` and updated to the real outcome inside
  the same transaction (`recordOutcome`, line 266); other sessions see only committed data, so they
  see no row at all or the final row. `storedResult` throws `IllegalStateException` if it ever sees
  `PENDING` (line 201), which would mean a bug, not a race.

Why `ON CONFLICT DO NOTHING` and not "catch the duplicate error"? In PostgreSQL any error aborts the
whole transaction; to catch a unique violation and carry on you would need a SAVEPOINT first (comment
at line 54).

Test for 50 threads on one id: `PostgresIdempotencyTest.java:63`. One posting, one transfer row, 50
identical answers.

## Two instances and restarts, versus the in-memory store

The engine's `IdempotencyStore` (`engine/.../idempotency/IdempotencyStore.java`) keeps one
`CompletableFuture` per id in a `ConcurrentHashMap`; `putIfAbsent` picks one owner (lines 29-41).
That is correct inside ONE process, and fast, but:

| | In-memory `IdempotencyStore` | `transfer` table |
| --- | --- | --- |
| Second server | does not know the first server's ids | shared: both servers ask the same database |
| Restart | everything forgotten (no persistence, no expiry, memory grows: line 19) | remembered, because it is stored with the money |
| Failure between "claim" and "post" | the claim and the posting are separate things that can disagree | claim, money and outcome commit or roll back together |
| Speed | nanoseconds | one extra insert per transfer |

Tests: two `PostgresLedger` objects over one database, overlapping ids in different orders
(`PostgresIdempotencyTest.java:82`); a brand-new connection pool after "the old server is gone"
(`:120`). The engine's store still has a job in front (cheap early duplicate answers, `QUEUE_FULL`
release), but the database is the source of truth.

## Why the payload is not compared

`transfer("p", A, B, 30)` followed by `transfer("p", A, B, 999)` returns the FIRST outcome and moves
30 (this is the TODO-ME test). The id is the whole key. This is a decision, not an oversight:
comparing payloads adds a failure mode (what do we return on mismatch?) and a second source of truth.

What storing a request hash would add: a column with, say, a SHA-256 of (source, target, amount) on
the `transfer` row; on a duplicate, compare and answer with an error ("id reused with different
data", HTTP 422 in some payment APIs) instead of silently returning the old result. That catches
client bugs (accidentally reusing an id). The cost: a schema change, a new outcome or exception, and
a decision about which fields count. A caller that wants this (the API, later) can check it itself.

## Caveat: `lock_timeout` and a duplicate behind a stuck original

The duplicate's waiting insert is a statement waiting on a lock, so `lock_timeout` (default 5 s,
line 48) applies to it. If the original is stuck (for example waiting on a long-held account row for
more than 5 s for a busy account row), the duplicate gives up with `55P03` and throws `LedgerDatabaseException`. Nothing
was stored for the duplicate and it is safe to retry: by then the original has committed (so the
retry gets its stored answer) or rolled back (so the retry does the work). The caller learns "ask
again", never a wrong answer. The wait holds no other lock yet, so it cannot join a deadlock (line
147).

## Ids compare as exact bytes

`id TEXT PRIMARY KEY` on the database's default collation: equality is exact, so `"Dup"` and `"dup"`,
or `"dup"` and `"dup "`, are different ids. Nothing is trimmed, lower-cased or Unicode-normalised
(`"e"` plus a combining accent is not the same as the single precomposed character). That is what an
idempotency key should do: the client picks the key, and we must not decide two keys are the same.
(This holds for PostgreSQL's usual deterministic collations; the schema uses no case-insensitive one.)

## Oracle equivalents

| PostgreSQL here | Oracle |
| --- | --- |
| `PRIMARY KEY` on `transfer.id` | the same: a primary key or unique constraint |
| `INSERT ... ON CONFLICT DO NOTHING`, check rows == 1 | plain `INSERT` and catch `ORA-00001` (`DUP_VAL_ON_INDEX` in PL/SQL); or `MERGE` with only a `WHEN NOT MATCHED` branch. Oracle fails just the statement, so no SAVEPOINT is needed |
| second insert of an uncommitted duplicate key waits for the first transaction | the same: Oracle blocks it until the first commits (then ORA-00001) or rolls back (then the insert succeeds) |
| `READ COMMITTED` (we set it) | the default; the next statement sees the commit |
| `lock_timeout` for that wait | an INSERT has no `WAIT n` clause (that belongs to `SELECT ... FOR UPDATE`), so bound the wait with a JDBC query timeout |
| `ON CONFLICT DO NOTHING` shortcut | curiosity: `INSERT /*+ IGNORE_ROW_ON_DUPKEY_INDEX(transfer(id)) */ ...` silently skips duplicate rows, like DO NOTHING |

## Interview questions

1. **State the idempotency-key rule and why it includes rejections.** Same id, same answer, forever;
   a new attempt needs a new id. If a rejection were forgotten, a retry could succeed later, so one
   instruction could be both "refused" and "paid". Only attempts that stored nothing (queue full,
   contention, rolled-back error) leave the id free, since nothing was decided.
2. **Two requests with the same id arrive at the same millisecond. What stops a double posting?** The
   primary key. Both insert the claim row; the second insert waits for the first transaction to end.
   If the first commits, the second inserts nothing, reads the stored row and returns it. If it
   rolls back, the second proceeds and does the work. One transaction gets past step 1.
3. **Why insert first, instead of SELECT to check and then INSERT?** Between the SELECT and the
   INSERT another transaction can slip in, so both see "not found" and both proceed. The insert is
   one atomic check-and-claim that the database enforces; the unique index is the guard.
4. **Why is this better than an in-memory key store or Redis?** The claim and the money commit in
   one transaction, so they cannot disagree after a crash. It survives restarts and is shared by every
   server. A separate store has two systems to keep consistent and a window where one succeeded
   and the other did not.
5. **What if the same id comes with a different amount?** The original outcome is returned and the
   new amount is ignored; the id is the whole key. To flag it, store a hash of the request on the
   `transfer` row and compare on a duplicate, returning an error on mismatch. That is a deliberate
   later addition, not in this ledger.

## What breaks if... (exercise)

Replace step 1 with `SELECT id FROM transfer WHERE id = ?`, then `INSERT` only if no row came back
(and keep the primary key). Predict first, then run `PostgresIdempotencyTest.java:63` (50 threads, one
id). With READ COMMITTED, many threads see "no row" at the same time and all go on to insert. The
primary key still lets only one commit, so the rest hit `23505` (unique violation) and fail with an
exception instead of returning the stored answer: you have "no double posting" by luck of the
constraint but callers get errors, not the original outcome. Now also drop the primary key (imagine
a table without it) and ask: how many postings, and which invariant test fails? Then think about
why SERIALIZABLE would also "fix" it, and why that costs retries.

## TODO-ME

`PostgresIdempotencyTest.REQ_DB_005_the_same_id_with_a_different_amount_returns_the_original_outcome`
(line 216) is left for you, currently `@Disabled`. Apply `transfer("p", A, B, 30)`, then
`transfer("p", A, B, 999)`; assert the second result equals the first and the balances moved by 30
only. In a comment, explain why the ledger does not compare payloads. Check: it passes on today's
code; make the replay path post anew (for example, skip the `claim` check) and it must fail. Remove
the `@Disabled` line.
