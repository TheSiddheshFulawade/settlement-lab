# v3-06 · Transactional outbox and the lease-based relay (V3-06)

## What it is, in plain words

When a transfer is posted, two things must happen: the database records the posting, and the general
ledger (GL, the downstream system) must be told. Those are two different systems. No single COMMIT
covers both.

The naive way is a **dual write**: write to the database, then call the GL.

```
 COMMIT posting  ---- crash here ---->  GL never hears about it   (money moved, no GL entry)
 call GL first, then COMMIT -- crash -> GL has an entry for a posting that never happened
```

Either order can lose or invent a message, and you cannot fix it with more code in the same place,
because the crash happens between the two steps.

The **transactional outbox** removes the second write from the critical path. The message is not sent;
it is written down, as a row in the same database, in the same transaction as the posting. A separate
process (the **relay**) later reads the rows and sends them. The database gives us "both or neither"
for free, because both rows are in one transaction.

## Part 1: the write side (V3-06, `PostgresLedger.java:486`)

`postWithOutbox` is the only way a posting is written, in both lock modes (pessimistic line 224,
optimistic line 310). It does two INSERTs on the caller's connection, inside the transfer's one
transaction:

```
INSERT INTO posting (...) RETURNING seq          -- insertPosting, :499
INSERT INTO outbox (posting_seq) VALUES (?)      -- INSERT_OUTBOX, :111
COMMIT                                           -- both durable at once, or ROLLBACK: neither
```

The invariant is written at `:478-484`: a posting and its outbox row commit together or not at all.
The schema backs it up (`V1__ledger.sql:46-54`): `outbox.posting_seq` is the primary key (at most one
row per posting) and a foreign key to `posting(seq)` (no message about a posting that does not exist).
Besides `posting_seq` the row has `attempts INT DEFAULT 0` and `next_attempt_at TIMESTAMPTZ DEFAULT
now()`, so a new row is due immediately.

Tests (`PostgresOutboxWriteTest.java`): one outbox row per posted transfer (`:29`), a replayed id writes
no second row (`:47`), rejected transfers write none (`:59`), a transaction aborted by a lock timeout
leaves no transfer, posting or outbox row (`:71`), and under concurrent load no posting lacks a row and
none is orphaned (`:93`).

## Part 2: the relay, step by step (`OutboxRelay.relayOnce`, line 135)

One pass has three steps. Only the first locks anything.

```
 STEP 1  CLAIM   one short transaction                                        claim(), :163
   SELECT o.posting_seq, p.transfer_id, p.source, p.target, p.amount
     FROM outbox o JOIN posting p ON p.seq = o.posting_seq
    WHERE o.next_attempt_at <= now()
    ORDER BY o.posting_seq LIMIT ?
    FOR UPDATE OF o SKIP LOCKED                                               CLAIM, :71
   UPDATE outbox SET next_attempt_at = now() + ? * interval '1 millisecond'
    WHERE posting_seq = ANY(?)                                                LEASE, :81
   COMMIT        <- row locks end here; the LEASE is the claim from now on

 STEP 2  PUBLISH   no connection held, no transaction open                    publish(), :151
   for each claimed row, lowest seq first: generalLedger.publish(posting)

 STEP 3  SETTLE   one tiny transaction per row                                settle(), :196
   accepted ->  DELETE FROM outbox WHERE posting_seq = ?                      DELETE, :85
   threw    ->  UPDATE outbox SET attempts = attempts + 1,
                    next_attempt_at = now() + ? * interval '1 millisecond'
                 WHERE posting_seq = ?                                        BACK_OFF, :88
```

Plain words for each piece:

- `FOR UPDATE OF o` locks only the outbox rows, not the posting rows (nothing ever changes a posting).
- `ORDER BY posting_seq LIMIT ?` takes the oldest due rows, at most `batchSize`.
- The lease `UPDATE` pushes `next_attempt_at` into the future. After COMMIT the row says "not due
  until later". That is the claim.
- A publish that throws any `RuntimeException` (error, timeout, outage, open circuit) is caught
  (`:155`) and does not stop the others. The row stays and is backed off. The relay does not know
  whether the GL stored it (a lost ack) and does not need to.
- `relayOnce` returns how many rows the GL accepted (`RECORDED` or `ALREADY_RECORDED`).
- `drain(timeout)` (`:214`) loops `relayOnce` until the outbox is empty, waiting 20 ms
  (`IDLE_POLL`) when a pass accepts nothing, because rows that are leased or backed off are not due yet.

## Part 3: `FOR UPDATE SKIP LOCKED`, the lease, and why two relays never publish the same row

Without `SKIP LOCKED`, a second relay running the same SELECT would **wait** behind the first one's row
locks, then take the same rows. With it, rows locked by someone else are passed over: no waiting, no
overlap. It turns the table into a work queue.

The row lock only lasts until COMMIT of step 1, but publishing happens after that. So the lock cannot be
the claim. The **lease** is: `next_attempt_at` is in the future, so the `WHERE next_attempt_at <= now()`
of any other relay does not select it. The invariant is at `OutboxRelay.java:36-43`: two relays never
publish a row while its lease is live. A second relay meets one of three states:

```
 Relay A claim transaction          Relay B runs the same SELECT ...
 ---------------------------        ---------------------------------------------------------
 (a) still open (rows locked)       B's SKIP LOCKED passes over those rows, no waiting.
 (b) committed (lease written)      B's WHERE is false for them: next_attempt_at is in the future.
     B's scan began BEFORE A's      B's snapshot still saw the old row. When B reaches it, the row
     commit                         is locked or changed by a transaction that has since committed.
                                    In READ COMMITTED, PostgreSQL re-checks B's WHERE against the
                                    newly committed row version before locking it. Now false:
                                    the row is skipped.
 (c) rolled back                    Nothing was claimed, so B taking the row is correct.
```

State (b) is the subtle one. The key fact: READ COMMITTED does not simply trust the snapshot for rows it
is about to lock. If the row was updated by a transaction that committed meanwhile, it re-evaluates the
WHERE on the latest version. That is the same rule as the version-check re-check in V3-05 (UPDATE waits,
then re-checks the WHERE). No SERIALIZABLE is needed (`OutboxRelay.java:52-54`); every transaction here
sets READ COMMITTED (`:123`).

Tests: `REQ_DB_007_a_row_with_a_live_lease_is_not_claimed_by_a_second_relay` (`OutboxRelayTest.java:125`):
relay one is stuck inside the GL call holding four leased rows; relay two returns 0 and makes 0 GL
calls. And `REQ_DB_007_two_relays_running_together_...` (`:168`): 300 postings, two relays of different
batch sizes, every transfer published exactly once (leases outlive the test, so no expiry can excuse a
second call).

```
 time ->
 Relay 1:  [claim 1..7 COMMIT]--publish 1..7 (no tx)--[delete][delete]...
 Relay 2:        [claim: skips 1..7 (future lease) -> takes 8..18 COMMIT]--publish 8..18--...
 Database: rows 1..7 and 8..18 each have next_attempt_at = now()+lease while being published.
```

## Part 4: why the GL call is outside every transaction (REQ-DB-009)

During step 2 no transaction is open and the connection is back in the pool (`:139-140`). The reason: a
network call to another system can be slow or hang. Inside a transaction that would mean:

- the outbox rows stay locked, so other relays and the settle statements wait;
- a pool connection is pinned for the whole call; with a small pool, a slow GL starves the ledger
  itself of connections;
- an idle-in-transaction session holds back the database's cleanup (vacuum).

Test: `REQ_DB_007_no_transaction_is_open_and_no_claimed_row_is_locked_during_the_gl_call`
(`OutboxRelayTest.java:199`). From inside the GL call it tries `SELECT ... FOR UPDATE NOWAIT` on the
claimed row (must succeed) and counts `idle in transaction` sessions in `pg_stat_activity` (must be 0).

## Part 5: at-least-once delivery, exactly-once effect

A row is deleted only AFTER the GL accepted it. So any failure between "GL stored it" and "DELETE
committed" (crash, lost ack, lease ran out) means the row is sent again. That is **at-least-once
delivery**: never lost, sometimes repeated. Exactly-once is not possible over a network, but
**exactly-once effect** is: the GL is keyed by transfer id, so the second send is answered
`ALREADY_RECORDED` and stores nothing (`OutboxRelay.java:45-50`). The cost of a repeat is one wasted
call, never money. The drain test with faults checks the GL matches the ledger's postings
(`OutboxRelayDrainTest.java:97`, flaky and duplicate-ack faults).

## Part 6: crash and restart recovery, and lease sizing

The outbox is in the database, not in the process (`:47`, `:56`). If a relay dies after claiming, its
rows carry a future `next_attempt_at`. When that time passes they are due again and any relay claims
them. Nobody has to detect the crash; the lease just runs out. After a full stop and a start on a new
connection pool, the new relay delivers each posting once
(`OutboxRelayDrainTest.java:41`, REQ-DB-008). That is why the lease is a timeout and not a flag: a
flag ("claimed = true") written by a dead relay would stay forever.

**Sizing the lease.** The lease is set once, at claim time, for the WHOLE batch, but the rows are
published one after another. So the last row of a batch gets its call after
(batch size x time per publish) has passed. The lease must be longer than the slowest batch, including
the retries and circuit-breaker waits inside the publisher (the javadoc at `:102-104` says the lease
must outlast them). Too short: the lease expires while the first relay is still working, a second relay
takes the same rows, and the GL gets duplicates (harmless but wasteful, and the first relay's later
DELETE finds 0 rows or deletes the row the second one is also sending). Too long: after a crash those
rows wait the whole lease before anyone retries. The constructor refuses a lease under 1 ms
(`:117`, tested at `OutboxRelayArgumentsTest.java:45`): a zero lease would let another relay claim a
row the instant the first commits.

## Part 7: failure back-off

When a publish throws, step 3 runs `BACK_OFF` (`:88`): `attempts + 1` and `next_attempt_at = now() +
lease`. So a failing row is not hammered every pass; it waits one lease. Other rows in the batch are
unaffected (`OutboxRelayTest.java:107`: only the failing posting stays). `attempts` is a counter you can
alert on; nothing here gives up on a row, because dropping a posting would lose a GL entry. (Note the
back-off is a fixed lease, not exponential: the retries with exponential waits live inside the
publisher; the relay's port is usually a `ResilientPublisher`, `OutboxRelay.java:103`.)

## Part 8: the in-memory `OutboxPublisher` versus the database outbox

| | In-memory (`gl/OutboxPublisher.java`, v1) | Database (`OutboxRelay`, V3-06) |
| --- | --- | --- |
| Where waiting messages live | a `LinkedBlockingDeque` in the JVM (`:35`) | the `outbox` table |
| Written with the posting? | no: `publish` is called after the ledger changed | yes: same transaction |
| Crash with messages waiting | lost; "v1 keeps them in memory only" (`:166`) | kept, redelivered after lease |
| Who sends | up to 16 sender virtual threads, then one relay thread for failures (`:22-26`) | any number of relays, any process |
| Retry timing | every `retryInterval`, head of the deque in order | `next_attempt_at` per row |
| Duplicates | possible, same fix: GL keyed by transfer id | same |

In database mode the engine uses a no-op `PostingPublisher` because the outbox row is already durable
(SPEC 16.4).

## Oracle equivalents

| PostgreSQL here | Oracle |
| --- | --- |
| `FOR UPDATE OF o SKIP LOCKED` | the same clause, Oracle 11g and later (`FOR UPDATE OF o.posting_seq SKIP LOCKED`). Same re-check of the WHERE under READ COMMITTED |
| `LIMIT ?` | `FETCH FIRST n ROWS ONLY` (12c+), but Oracle refuses it together with `FOR UPDATE` (ORA-02014), and `ROWNUM` is applied before rows are skipped. The pattern is: open the cursor with no limit and fetch only n rows (Oracle locks a row when it is fetched), or use a subquery of ids and then lock them. The code comment at `OutboxRelay.java:66-69` says this |
| `now() + ? * interval '1 millisecond'` | `SYSTIMESTAMP + NUMTODSINTERVAL(?, 'SECOND')` or `+ INTERVAL '5' SECOND` |
| `posting_seq = ANY(?)` with a JDBC array | an `IN (...)` list or a collection type |
| `TIMESTAMPTZ DEFAULT now()` | `TIMESTAMP WITH TIME ZONE DEFAULT SYSTIMESTAMP` |
| the outbox table plus relay polling | **Oracle Advanced Queuing (AQ)**, called **Transactional Event Queues (TxEventQ)** in newer releases, is the built-in queue: `DBMS_AQ.ENQUEUE` inside the same transaction as the business write, consumers dequeue with locking and skip built in, retry delay and max retries are queue options. It is the Oracle-native outbox; the hand-made table is the portable one |
| `now()` is the transaction's start time | `SYSTIMESTAMP` is the current time; `SYSDATE` has no time zone. Check which clock you compare with when sizing leases |

## Interview questions

1. **What is the dual-write problem and how does the transactional outbox solve it?** Writing to the
   database and calling another system are two separate operations, and a crash between them leaves one
   done and one not: a lost message or a message about nothing. The outbox writes the message as a row
   in the same database transaction as the business change, so both commit or neither does. A relay
   sends it afterwards. Delivery is at-least-once, and the receiver must be idempotent.
2. **How do two relays avoid sending the same row?** The claim uses `FOR UPDATE SKIP LOCKED`, so rows
   another relay has locked are skipped instead of waited on. The claim transaction also writes a lease
   (`next_attempt_at` in the future) and commits, so after the lock is gone the lease keeps the row out
   of every other relay's `WHERE next_attempt_at <= now()`. If a second relay's scan started before the
   first one's commit, READ COMMITTED re-checks the WHERE on the committed row version and skips it.
3. **Why not call the GL inside the claim transaction and keep the lock?** The call goes over the
   network and may be slow or hang. The transaction would pin a pool connection and hold row locks the
   whole time, so a slow GL could exhaust the pool and block settles. Claiming with a lease means the
   locks last milliseconds. The price is that the claim is now a time limit instead of a lock, so we
   must size the lease and accept that an expired lease can cause a duplicate send.
4. **A relay crashes after claiming rows. What happens?** Nothing special is needed. Its rows have
   `next_attempt_at` in the future. When the lease passes, the rows are due again and any relay claims
   them and sends again. The GL is keyed by transfer id, so if the first send had actually landed, the
   answer is `ALREADY_RECORDED` and nothing is stored twice. The only cost is the delay of one lease.
5. **How do you choose the lease, and what if it is too short or too long?** Longer than the slowest
   batch: the lease starts at claim time for the whole batch, and rows are published one after another,
   each with its retries. Too short: the lease expires mid-batch, another relay takes the same rows and
   the GL sees duplicates; money is safe but work is wasted. Too long: rows of a crashed relay wait
   a long time before anyone retries. Failed rows also wait one lease before the next try.

## What breaks if... (exercise)

Option A: delete `SKIP LOCKED` from `CLAIM` (line 75) so it reads only `FOR UPDATE OF o`. Predict first,
then run `REQ_DB_007_a_row_with_a_live_lease_is_not_claimed_by_a_second_relay` and the two-relays test
(`OutboxRelayTest.java:125`, `:168`). With the lease still in place, a second relay's WHERE excludes
leased rows once the first claim has committed, so what exactly is the window where the missing SKIP
LOCKED matters, and what does the second relay do during it (wait? take the rows? fail with
`lock_timeout`)? Is there any case of delivery that is now worse, or only throughput?

Option B: move the publish loop inside `claim()`'s transaction (publish before COMMIT, delete in the
same transaction) and drop the lease. Money stays safe. Which test fails, which resource is held
during the GL call, and what happens to the pool when the GL gets slow and several relays run?

## TODO-ME

`OutboxRelayTest.REQ_DB_007_a_crashed_relays_lease_expires_and_another_relay_picks_the_row_up`
(line 239) is left for you, currently `@Disabled` with an empty body. Seed two postings, simulate the
crash by hand with `UPDATE outbox SET next_attempt_at = now() + interval '1 second' WHERE posting_seq =
1`, assert a live relay publishes only seq 2 at once, then with Awaitility (not `Thread.sleep`) that a
later `relayOnce()` publishes seq 1 and the outbox is empty. Check: it passes today; make `CLAIM` ignore
`next_attempt_at` and the first assertion must fail. Remove the `@Disabled`.
