-- V1: the ledger schema (SPEC.md section 16.2). An applied migration is never edited (REQ-DB-011):
-- a change is a new V2 file. Flyway stores a checksum of this file and refuses to run if it changes.
--
-- Money is BIGINT minor units everywhere (REQ-DB-002). No NUMERIC, no floating point, no MONEY type.
-- Oracle equivalent: NUMBER(19,0); same rule, a whole number of minor units.

CREATE TABLE account (
    id      BIGINT PRIMARY KEY,
    -- The last line of defence for "no account goes below zero": even a buggy statement cannot commit
    -- a negative balance. The application checks funds first; this makes a bug fail loudly.
    balance BIGINT NOT NULL CONSTRAINT account_balance_not_negative CHECK (balance >= 0),
    -- Optimistic mode: UPDATE ... WHERE id = ? AND version = ?; zero rows updated means someone else
    -- got there first. Pessimistic mode ignores it. (Oracle: the same column, or ORA_ROWSCN.)
    version BIGINT NOT NULL DEFAULT 0
);

-- One row per transfer id, for every outcome: this is the idempotency record (REQ-DB-005).
-- The primary key is the idempotency guarantee: a second insert of the same id fails with SQLState
-- 23505 (unique violation) and the caller returns the stored outcome. (Oracle: ORA-00001.)
CREATE TABLE transfer (
    id          TEXT PRIMARY KEY,
    outcome     TEXT NOT NULL,
    -- Set when the transfer was posted; stays NULL for a rejection. Deliberately no foreign key: the
    -- transfer row is created first and the posting second, so pointing both ways would be circular.
    posting_seq BIGINT
);

-- The ledger's postings, in sequence order.
CREATE TABLE posting (
    -- Identity column: the database hands out increasing numbers, so postings have a stable order
    -- without a lock on a counter row. A sequence can commit out of order and leaves gaps after a
    -- rollback; use it for ordering and keys, never for "no gaps". (Oracle 12c+: GENERATED ALWAYS AS
    -- IDENTITY, the same syntax; older Oracle: a SEQUENCE and a trigger.)
    seq         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    -- UNIQUE: a transfer id can be posted at most once, even if the application logic had a bug.
    -- NOT NULL, because a UNIQUE column alone allows many NULLs. The foreign key means a posting
    -- always belongs to a recorded transfer.
    transfer_id TEXT NOT NULL UNIQUE REFERENCES transfer (id),
    source      BIGINT NOT NULL,
    target      BIGINT NOT NULL,
    -- A posting moves a positive amount; zero or negative would be a bug or a reversed transfer.
    amount      BIGINT NOT NULL CONSTRAINT posting_amount_positive CHECK (amount > 0)
);

-- Postings not yet accepted by the general ledger (the transactional outbox, REQ-DB-006).
CREATE TABLE outbox (
    -- Primary key = at most one outbox row per posting. The foreign key means an outbox row can never
    -- exist without its posting, and a posting that still has one cannot be deleted.
    posting_seq     BIGINT PRIMARY KEY REFERENCES posting (seq),
    attempts        INT NOT NULL DEFAULT 0,
    -- The relay claims rows where next_attempt_at <= now() with FOR UPDATE SKIP LOCKED, then pushes
    -- this forward as a lease while it calls the GL. (Oracle: the same SKIP LOCKED clause, 11g+.)
    next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
