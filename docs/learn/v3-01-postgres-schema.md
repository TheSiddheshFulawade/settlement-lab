# v3-01 · The PostgreSQL module and its schema (V3-01)

## What it is, in plain words

**The module.** `ledger-postgres` is a third Maven module next to `engine` and `api`. It depends on
the engine, never the other way round. `ArchitectureTest` (`ArchitectureTest.java:23-56`) enforces
that: the adapter may use only `java`, `javax.sql`, our own packages and Flyway; no Spring, no JPA or
Hibernate (plain JDBC, so every SQL statement is visible); and nothing outside it may import it. It is
separate so the engine stays free of any database. At V3-01 it holds only the schema and
`LedgerSchema.migrate` (`LedgerSchema.java:25`).

**The four tables** (`V1__ledger.sql`):

```
account (id PK, balance >= 0, version)
transfer (id PK, outcome, posting_seq)        <- idempotency record
posting (seq identity PK, transfer_id UNIQUE NOT NULL -> transfer.id, source, target, amount > 0)
outbox (posting_seq PK -> posting.seq, attempts, next_attempt_at)
```

- **Money is `BIGINT` minor units** (lines 4, 11, 42). No `NUMERIC`, no `MONEY`, no floating point.
  Tests check that no column has such a type and that `balance` and `amount` are `bigint`
  (`SchemaTest.java:38-53`).
- **`CHECK (balance >= 0)`** (line 11) is the last line of defence. The application already checks
  funds before it debits. The CHECK means that if that code ever has a bug, the database refuses to
  commit a negative balance and the bug fails loudly instead of quietly creating money. Zero is
  allowed; an `UPDATE` that would go below zero fails and the balance stays as it was
  (`SchemaTest.java:56-75`).
- **`transfer.id` is the primary key** (line 21). One row per transfer id, whatever the outcome. A
  second insert of the same id fails with SQLState `23505`, and the caller then returns the stored
  outcome. The database settles two threads racing on one id, not our code.
- **`posting.transfer_id` is `UNIQUE`, `NOT NULL` and a foreign key** (line 38). UNIQUE: one posting per
  transfer even if the logic is wrong. NOT NULL: a UNIQUE column alone allows many NULLs (in both
  PostgreSQL and Oracle, rows with a NULL in a unique column do not clash). FK: a posting always
  belongs to a recorded transfer.
- **`transfer.posting_seq` has no foreign key** on purpose (lines 23-25). The transfer row is written
  first and the posting second, so links in both directions would be circular.
- **`posting.seq` is an identity column** (line 34). The database hands out increasing numbers, so
  postings have an order with no lock on a counter row. Numbers can commit out of order and a rolled
  back insert leaves a gap. Use it for ordering and keys, never for "no gaps".
- **`outbox.posting_seq` is primary key and foreign key** (line 49): at most one outbox row per posting,
  never an outbox row without its posting, and a posting that still has an outbox row cannot be
  deleted. REQ-DB-006 (posting and outbox row exist together) leans on this.
- **`version`** (line 14) is for optimistic mode, later. `next_attempt_at` is for the relay's
  `SKIP LOCKED` claim, later.

**Flyway.** Schema changes are numbered SQL files, `V<n>__what.sql`, under `db/migration`. Flyway
records each applied file and its checksum in `flyway_schema_history` and refuses to run if an applied
file changed. `SchemaTest.java:27` pins the V1 checksum in a constant, so an edit fails a test with a
clear message. Rule (REQ-DB-011): never edit an applied migration; add `V2`. `LedgerSchema` sets
`cleanDisabled(true)` (`LedgerSchema.java:29`), so production code cannot wipe the ledger. Only the
test base turns clean on (`PostgresTestBase.java:65`). Since Flyway 10 each database is its own
module, so `flyway-database-postgresql` is on the runtime classpath (`pom.xml:62-68`).

**Testcontainers.** `PostgresTestBase` starts a real PostgreSQL in Docker (`postgres:18.7-alpine3.24`,
line 33), one container per test class, and runs Flyway `clean` then `migrate` before each test, so
every test starts from a fresh schema. We use real PostgreSQL, not H2, because H2 imitates it:
identity columns, SQLStates, locking and `SKIP LOCKED` behave differently, and v3 is about exactly
those. `@Testcontainers(disabledWithoutDocker = false)` (line 25) means no Docker gives a failure,
not a skip, so a missing Docker can never make a green build that tested nothing.

**The `db` profile.** By default the module excludes tests tagged `database` and skips JaCoCo
(`pom.xml:22-23`). `-Pdb` runs them and turns JaCoCo on (`pom.xml:31-36`). JaCoCo is skipped rather
than run on an empty set: with no database tests the module's code is unmeasured and the 85 % gate
would either fail or have to be loosened. CI passes `-Pdb` on Ubuntu only (`verify.yml:57`) because
Windows runners cannot run Linux containers. Windows still runs `ArchitectureTest` and
`ImageTagSyncTest`, which need no container.

**Image-tag sync.** CI pre-pulls the image with retries (`verify.yml:46-54`) so a flaky registry does
not fail tests. `ImageTagSyncTest` checks that the workflow contains `docker pull` plus the exact tag
from `PostgresTestBase.POSTGRES_IMAGE`. If they drift, the pre-pull quietly stops helping; the test
makes that loud.

## Oracle equivalents

| PostgreSQL | Oracle | Note |
|---|---|---|
| `BIGINT` | `NUMBER(19,0)` | Whole minor units in both. Oracle has no separate 64-bit integer type; `NUMBER` with scale 0 plays that role. |
| `TEXT` | `VARCHAR2(n)` or `CLOB` | Oracle needs a length for `VARCHAR2` (4000 bytes is the usual limit); `CLOB` for large text. Pick `VARCHAR2` for ids. |
| `CHECK (balance >= 0)` | the same `CHECK` | Same syntax and idea. |
| `GENERATED ALWAYS AS IDENTITY` | the same, from 12c; before that a `SEQUENCE` plus a trigger | Gaps are allowed in both. |
| `TIMESTAMPTZ` | `TIMESTAMP WITH TIME ZONE` | Check how each driver maps it to Java. |
| SQLState `23505` unique, `23514` check, `23503` foreign key | `ORA-00001`, `ORA-02290`, `ORA-02291` | `ORA-02291` is a missing parent on insert. Deleting a parent that still has children is `ORA-02292` ("child record found"). That is our delete case; PostgreSQL uses `23503` for both. In JDBC Oracle also exposes the code through `getErrorCode()`. |
| Default `READ COMMITTED` | Default `READ COMMITTED` | Same name. Neither allows dirty reads. PostgreSQL gives each statement a snapshot via MVCC row versions; Oracle rebuilds old data from undo for read consistency. Details differ, so check before claiming more. |
| Transactional DDL: a failed migration rolls back whole | DDL commits implicitly | In Oracle a migration that fails halfway leaves earlier statements applied, and Flyway marks it failed. You repair by hand (Flyway's `repair` plus manual cleanup). |
| `SELECT ... FOR UPDATE SKIP LOCKED` | the same, available since 11g | Used by the outbox relay. Check it is supported on the exact Oracle version. |
| `lock_timeout` (session setting) | `FOR UPDATE WAIT n` or `NOWAIT` per statement | PostgreSQL bounds the wait for any lock; Oracle states it on the locking statement. PostgreSQL also has `FOR UPDATE NOWAIT`. |
| Testcontainers `postgres` image | Testcontainers Oracle module with `gvenzl/oracle-free` | Same pattern: a throwaway real database per test class. The image is much larger and slower to start. Check the module and image tag names when you use them. |

## The TODO-ME for you

In `SchemaTest.java:158-162` write `postingWithOutboxRowCannotBeDeleted`.

**What it should prove:** a posting that still has an outbox row cannot be deleted. The foreign key
from `outbox.posting_seq` to `posting.seq` blocks the `DELETE` with SQLState `23503`
(`FOREIGN_KEY_VIOLATION` is already a constant in the file).

**Why it matters:** REQ-DB-006 says a posting and its outbox row exist together. The relay deletes the
outbox row only after the general ledger accepts it. If nothing stopped a posting from being deleted
first, a bug could leave an outbox row pointing at nothing, or a posting that never reaches the GL.

**Hints:**
- Look at `outboxRowDefaultsToZeroAttemptsAndDueNow` (line 130). It already builds a transfer, a
  posting and an outbox row.
- Use the `sqlStateOf` helper, which returns the SQLState or `null` if the statement worked.
- Optional extra: after the failed delete, check the posting is still there.

**Run it:** `./mvnw verify -Pdb` from the repo root, or `./mvnw -pl ledger-postgres -am verify -Pdb`.
Docker must be running. Then check your test can fail: in a scratch copy, remove `REFERENCES posting
(seq)` from the outbox table and see your test go red. Put it back. Do not edit the applied V1 file in
the repo; the checksum test would fail.

## Interview questions

1. **The code already checks funds. Why also a `CHECK (balance >= 0)`?**
   Defence in depth. The application check can have a bug, a race or be bypassed by another writer, a
   script or a later feature. The database is the one place every write must pass, so it holds the
   invariant that money never goes negative. The cost is tiny and a bug fails loudly at commit
   instead of silently corrupting data.
2. **Why test against a real PostgreSQL (Testcontainers) and not H2?**
   H2 only imitates PostgreSQL. Error codes, identity behaviour, locking, `SKIP LOCKED` and DDL
   rules differ, and this project is about locking and idempotency. A test that passes on H2 can fail
   in production. The price is Docker and a slower start, so we use one container per class and
   reset the schema with Flyway between tests.
3. **Why never edit an applied migration, and what do you do instead?**
   Flyway stores a checksum for each applied file and refuses to start if it changes. Other
   environments have already run the old text, so an edit would make databases differ while the
   file looks the same. Add a new `V2__...sql` that changes the schema going forward. We also pin
   the V1 checksum in a test so the mistake is caught in CI.
4. **Why is the idempotency record a primary key and not SELECT, then INSERT?**
   Two requests with the same id can both run the SELECT, both see nothing and both insert: a
   check-then-act race. A primary key makes the database decide: one insert wins and the other gets
   `23505`, which we turn into "return the stored outcome". It also works across several
   application instances and after a restart, because the state lives in the database.
5. **Why do the database tests run on Ubuntu only, and how do you keep Windows honest?**
   Windows CI runners cannot run Linux containers. The Windows jobs run everything that needs no
   container, including the architecture rules and the image-tag test. The `database` tests and the
   module's coverage gate run on Ubuntu with `-Pdb`. JaCoCo is skipped by default instead of
   passing on zero tests, and with `-Pdb` and no Docker the tests fail, not skip, so no job can go
   green while having tested nothing.

## What breaks if...

You change `posting.transfer_id` to allow NULL (drop `NOT NULL`) but keep `UNIQUE`. Which test, if
any, fails today? What bad rows could now exist, and why would the unique key no longer protect you
from them? Try it on a scratch copy and see which `SchemaTest` tests notice. If none does, what test
would you add?
