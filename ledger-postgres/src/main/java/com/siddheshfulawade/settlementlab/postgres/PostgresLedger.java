package com.siddheshfulawade.settlementlab.postgres;

import com.siddheshfulawade.settlementlab.domain.AccountId;
import com.siddheshfulawade.settlementlab.domain.ApplyResult;
import com.siddheshfulawade.settlementlab.domain.Ledger;
import com.siddheshfulawade.settlementlab.domain.Money;
import com.siddheshfulawade.settlementlab.domain.Outcome;
import com.siddheshfulawade.settlementlab.domain.Posting;
import com.siddheshfulawade.settlementlab.domain.Transfer;
import com.siddheshfulawade.settlementlab.domain.TransferId;
import com.siddheshfulawade.settlementlab.domain.TransferValidator;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.IntConsumer;
import javax.sql.DataSource;

/**
 * A {@link Ledger} whose balances, transfers and postings live in PostgreSQL (SPEC.md 16.3), with
 * plain JDBC so every statement and lock is visible.
 *
 * <p>Two modes ({@link LockMode}). <b>Pessimistic</b>: lock both account rows with {@code SELECT ...
 * FOR UPDATE}, lower id first, then check and update. <b>Optimistic</b>: read both rows without a
 * lock, then update each only {@code WHERE version = ?} the version that was read; if either update
 * changes zero rows someone else wrote first, so the whole attempt rolls back and is retried after a
 * jittered back-off, at most {@value #MAX_OPTIMISTIC_ATTEMPTS} attempts (REQ-DB-004, 004a).
 *
 * <p><b>Isolation: READ COMMITTED</b>, set explicitly on every transaction so a pool configured
 * otherwise cannot change the behaviour. It is enough because correctness does not rest on what a
 * snapshot shows: in pessimistic mode the row locks serialise every writer of an account, and {@code
 * SELECT ... FOR UPDATE} returns the latest committed balance after it waits; in optimistic mode the
 * version check refuses any write based on a stale read. SERIALIZABLE would add 40001 retries for no
 * extra safety. Oracle: READ COMMITTED is its default, with the same row-lock rules.
 *
 * <p><b>One connection per call</b>, taken from the caller's pool and handed back before the call
 * returns, never two at once: a call that held one connection while waiting for a second could
 * exhaust the pool and stall every worker. So the pool needs at least one connection per worker.
 *
 * <p>No network call other than to the database happens inside a transaction (REQ-DB-009), and the
 * constructor creates and seeds nothing: the schema is Flyway's ({@link LedgerSchema}).
 */
public final class PostgresLedger implements Ledger {

    /** Generous enough for the stress suite: under load a hot row may queue many transfers. */
    static final Duration DEFAULT_LOCK_TIMEOUT = Duration.ofSeconds(5);

    static final Duration DEFAULT_STATEMENT_TIMEOUT = Duration.ofSeconds(30);

    /** REQ-DB-004a: after this many conflicting attempts the answer is REJECTED_CONTENTION. */
    static final int MAX_OPTIMISTIC_ATTEMPTS = 10;

    /** Back-off ceiling after the first conflict; it doubles per conflict up to the cap. */
    static final Duration BACKOFF_BASE = Duration.ofMillis(1);

    /** The longest single back-off: nine back-offs (no sleep after the tenth) stay well under 1 s. */
    static final Duration BACKOFF_CAP = Duration.ofMillis(50);

    /**
     * Step 1: claim the transfer id. Zero rows inserted means the id is already recorded. ON CONFLICT
     * DO NOTHING rather than catching 23505: in PostgreSQL any error aborts the whole transaction, so
     * catching the unique violation would need a SAVEPOINT first. Oracle has no ON CONFLICT; a plain
     * INSERT that catches ORA-00001 (DUP_VAL_ON_INDEX) works there, as Oracle fails only the statement.
     */
    private static final String CLAIM =
            "INSERT INTO transfer (id, outcome) VALUES (?, 'PENDING') ON CONFLICT (id) DO NOTHING";

    /**
     * Step 2: both account rows, locked lower id first. PostgreSQL sorts the rows before it locks them
     * (the locking step runs on the ORDER BY output), so ORDER BY id is the lock order.
     */
    private static final String LOCK_ACCOUNTS =
            "SELECT id, balance FROM account WHERE id IN (?, ?) ORDER BY id FOR UPDATE";

    /** Relative change, so the database does the arithmetic on the row it has locked. */
    private static final String MOVE_MONEY = "UPDATE account SET balance = balance + ? WHERE id = ?";

    /**
     * Optimistic step 2: both rows in one statement, so both come from one snapshot. No FOR UPDATE:
     * this read takes no lock and never waits for a writer. Oracle: the same plain SELECT.
     */
    private static final String READ_ACCOUNTS = "SELECT id, balance, version FROM account WHERE id IN (?, ?)";

    /**
     * Optimistic step 3, once per account. Zero rows changed means the version moved since the read.
     * Oracle: the same statement; SQL%ROWCOUNT (or the JDBC update count) is 0 on a conflict.
     */
    private static final String VERSIONED_UPDATE =
            "UPDATE account SET balance = ?, version = version + 1 WHERE id = ? AND version = ?";

    private static final String INSERT_POSTING =
            "INSERT INTO posting (transfer_id, source, target, amount) VALUES (?, ?, ?, ?) RETURNING seq";

    private static final String RECORD_OUTCOME = "UPDATE transfer SET outcome = ?, posting_seq = ? WHERE id = ?";

    private static final String STORED_RESULT = "SELECT t.outcome, p.seq, p.source, p.target, p.amount"
            + " FROM transfer t LEFT JOIN posting p ON p.seq = t.posting_seq WHERE t.id = ?";

    private static final String SNAPSHOT = "SELECT id, balance FROM account ORDER BY id";
    private static final String BALANCE = "SELECT balance FROM account WHERE id = ?";
    private static final String POSTINGS = "SELECT seq, transfer_id, source, target, amount FROM posting ORDER BY seq";

    private final DataSource dataSource;

    private final LockMode mode;

    /** Failed optimistic attempts (each rolled back), retried or not. Always 0 in pessimistic mode. */
    private final LongAdder conflicts = new LongAdder();

    /** Test seam: runs after the optimistic read, before the versioned updates. Default: nothing. */
    private volatile IntConsumer afterReadHook = attempt -> {};

    /**
     * Sent first in every transaction. SET LOCAL lasts until COMMIT or ROLLBACK, so the limits never
     * stay on the pooled connection for its next user. Built from two longs, so nothing to escape.
     */
    private final String transactionSettings;

    /** Lock timeout 5 s, statement timeout 30 s. */
    public PostgresLedger(DataSource dataSource, LockMode mode) {
        this(dataSource, mode, DEFAULT_LOCK_TIMEOUT, DEFAULT_STATEMENT_TIMEOUT);
    }

    /**
     * @param lockTimeout the longest one statement waits for a row lock (PostgreSQL {@code
     *     lock_timeout}, SQLState 55P03 when it fires). Oracle: {@code SELECT ... FOR UPDATE WAIT n}.
     * @param statementTimeout the longest one statement runs, lock waits included ({@code
     *     statement_timeout}, SQLState 57014). Oracle: JDBC {@code setQueryTimeout} or Resource Manager.
     */
    public PostgresLedger(DataSource dataSource, LockMode mode, Duration lockTimeout, Duration statementTimeout) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.mode = Objects.requireNonNull(mode, "mode");
        this.transactionSettings = "SET TRANSACTION ISOLATION LEVEL READ COMMITTED;"
                + " SET LOCAL lock_timeout = '" + millis("lockTimeout", lockTimeout) + "ms';"
                + " SET LOCAL statement_timeout = '" + millis("statementTimeout", statementTimeout) + "ms'";
    }

    /**
     * In PostgreSQL a timeout of 0 means "no limit", so anything under 1 ms is refused rather than
     * silently unbounded; the upper limit is PostgreSQL's (an int of milliseconds).
     */
    private static long millis(String name, Duration timeout) {
        Objects.requireNonNull(timeout, name);
        if (timeout.compareTo(Duration.ofMillis(1)) < 0
                || timeout.compareTo(Duration.ofMillis(Integer.MAX_VALUE)) > 0) {
            throw new IllegalArgumentException(name + " must be from 1 ms to " + Integer.MAX_VALUE + " ms: " + timeout);
        }
        return timeout.toMillis();
    }

    /**
     * SPEC.md 16.3. Every outcome that reaches the database is stored on the id's {@code transfer}
     * row and replayed for the same id (REQ-DB-005): POSTED, REJECTED_FUNDS, and REJECTED_INVALID for
     * an unknown account. A lock or statement timeout, or any other error, rolls everything back, the
     * claim included, and throws {@link LedgerDatabaseException}; the id stays free, so the same id
     * can then be retried. Optimistic mode may also answer REJECTED_CONTENTION, which stores nothing.
     */
    @Override
    public ApplyResult applyWithResult(Transfer transfer) {
        // Amount and same-account rules need no database; account existence is checked by step 2.
        // These are rejected before any database call, so nothing is stored for the id. That is safe:
        // the answer depends only on the transfer itself, so a replay gets the same answer anyway.
        if (TransferValidator.validate(transfer, id -> true).isPresent()) {
            return ApplyResult.rejected(Outcome.REJECTED_INVALID);
        }
        return mode == LockMode.PESSIMISTIC ? applyPessimistic(transfer) : applyOptimistic(transfer);
    }

    /** One transaction: claim, lock both rows lower id first, check, move, post, record. */
    private ApplyResult applyPessimistic(Transfer transfer) {
        return inTransaction("apply transfer " + transfer.id(), connection -> {
            // Step 1. Invariant (REQ-DB-005): one transaction per id gets past this line. A concurrent
            // insert of the same id waits here, on the primary key, until the other transaction ends:
            // if it committed, we insert nothing and read its row (READ COMMITTED: the next statement
            // sees every commit that finished before it began); if it rolled back, our insert goes
            // ahead. The row is only visible once it holds the final outcome, so no one sees PENDING.
            // This wait is the first statement and holds no lock yet, so it cannot join a deadlock.
            if (!claim(connection, transfer.id())) {
                return storedResult(connection, transfer.id());
            }

            // Step 2. Invariant (REQ-DB-003): every transfer locks its accounts in ascending id order.
            // A deadlock needs a cycle of transactions, each holding a row the next one wants; a
            // transaction holding row k only ever waits for a row above k, so the waits always climb
            // and never close a cycle. Opposing transfers A->B and B->A both lock A first.
            Map<Long, Long> locked = lockAccounts(connection, transfer);
            if (locked.size() < 2) {
                // Unknown source or target: store REJECTED_INVALID like REJECTED_FUNDS, so the same id
                // gets the same answer later even if the account is opened in between (REQ-DB-005).
                recordOutcome(connection, transfer.id(), Outcome.REJECTED_INVALID, null);
                return ApplyResult.rejected(Outcome.REJECTED_INVALID);
            }

            // Step 3. Both rows are locked until COMMIT: no other transaction can change these two
            // balances, so the check below and the two updates are one atomic step (no lost update).
            long amount = transfer.amount().minorUnits();
            if (locked.get(transfer.source().value()) < amount) {
                // REJECTED_FUNDS stores only the outcome, so the same id gets the same answer later.
                recordOutcome(connection, transfer.id(), Outcome.REJECTED_FUNDS, null);
                return ApplyResult.rejected(Outcome.REJECTED_FUNDS);
            }
            // The CHECK (balance >= 0) on account is the last line of defence: if the check above
            // were ever wrong, this update fails with 23514 and the whole transfer rolls back.
            moveMoney(connection, transfer.source(), transfer.target(), amount);
            Posting posting = insertPosting(connection, transfer);
            recordOutcome(connection, transfer.id(), Outcome.POSTED, posting.sequence());
            return ApplyResult.posted(posting);
        });
    }

    /**
     * Up to {@value #MAX_OPTIMISTIC_ATTEMPTS} attempts, each its own short transaction on its own
     * borrowed connection. The back-off sleeps BETWEEN transactions, with the connection handed back
     * and no lock or claim held, so a waiting transfer never pins a pool connection or a row.
     */
    private ApplyResult applyOptimistic(Transfer transfer) {
        for (int attempt = 1; attempt <= MAX_OPTIMISTIC_ATTEMPTS; attempt++) {
            int thisAttempt = attempt;
            try {
                return inTransaction(
                        "apply transfer " + transfer.id() + " (attempt " + attempt + ")",
                        connection -> optimisticAttempt(connection, transfer, thisAttempt));
            } catch (VersionConflict conflict) {
                // inTransaction has already rolled back: balances, posting AND the claim row are gone,
                // so a concurrent duplicate waiting on the claim now goes ahead with a clean slate.
                conflicts.increment();
            }
            if (attempt < MAX_OPTIMISTIC_ATTEMPTS && !backOff(attempt)) {
                break; // interrupted: stop retrying and answer as if the attempts ran out
            }
        }
        // REQ-DB-004a: nothing is stored for the id (every attempt rolled back), so it stays free and
        // the engine releases its in-memory claim; a later retry with the same id is processed fresh.
        return ApplyResult.rejected(Outcome.REJECTED_CONTENTION);
    }

    /**
     * One optimistic attempt. Throws {@link VersionConflict} when either versioned update changes no
     * row, so {@link #inTransaction} rolls the whole attempt back, the claim included.
     */
    private ApplyResult optimisticAttempt(Connection connection, Transfer transfer, int attempt) throws SQLException {
        // Step 1, exactly as in pessimistic mode (REQ-DB-005): one transaction per id gets past here.
        // A concurrent duplicate waits on our uncommitted claim row; if this attempt conflicts and
        // rolls back, the claim disappears and the duplicate's insert goes ahead instead.
        if (!claim(connection, transfer.id())) {
            return storedResult(connection, transfer.id());
        }

        // Step 2: a plain read, no row lock. Another transaction may change these rows from here on.
        Map<Long, VersionedBalance> read = readAccounts(connection, transfer);
        afterReadHook.accept(attempt);
        if (read.size() < 2) {
            // Unknown source or target: stored, as in pessimistic mode (REQ-DB-005).
            recordOutcome(connection, transfer.id(), Outcome.REJECTED_INVALID, null);
            return ApplyResult.rejected(Outcome.REJECTED_INVALID);
        }

        // Both rows came from one statement, so from one snapshot: at that instant the source really
        // held this balance. A rejection writes no account, so it is correct as of the read and is
        // stored (REQ-DB-005); a posting is checked against later writes by the version below.
        long amount = transfer.amount().minorUnits();
        VersionedBalance source = read.get(transfer.source().value());
        VersionedBalance target = read.get(transfer.target().value());
        if (source.balance() < amount) {
            recordOutcome(connection, transfer.id(), Outcome.REJECTED_FUNDS, null);
            return ApplyResult.rejected(Outcome.REJECTED_FUNDS);
        }

        // Step 3. Invariant (REQ-DB-004, no lost update): each new balance is computed from the read
        // and written only WHERE the version is still the one read. Any transfer that wrote the row
        // since then raised the version when it committed, so our update changes zero rows and the
        // stale result is never written. If that writer has not committed yet, our UPDATE waits for
        // its row lock and then, in READ COMMITTED, re-checks the WHERE against the row it left:
        // committed, the version moved and we change zero rows; rolled back, the version still matches.
        // (Oracle: same outcome; its write consistency restarts the UPDATE when the row changed.)
        //
        // Invariant (no deadlock): the UPDATEs lock rows, so they run lower id first, like the
        // pessimistic FOR UPDATE. A transaction holding row k only waits for a row above k, so the
        // waits always climb and never close a cycle.
        // addExact: an overflowing target balance must fail loudly, not wrap to a negative number.
        long sourceBalance = Math.subtractExact(source.balance(), amount);
        long targetBalance = Math.addExact(target.balance(), amount);
        boolean bothUpdated = transfer.source().value() < transfer.target().value()
                ? versionedUpdate(connection, transfer.source(), sourceBalance, source.version())
                        && versionedUpdate(connection, transfer.target(), targetBalance, target.version())
                : versionedUpdate(connection, transfer.target(), targetBalance, target.version())
                        && versionedUpdate(connection, transfer.source(), sourceBalance, source.version());
        if (!bothUpdated) {
            throw VersionConflict.INSTANCE;
        }
        Posting posting = insertPosting(connection, transfer);
        recordOutcome(connection, transfer.id(), Outcome.POSTED, posting.sequence());
        return ApplyResult.posted(posting);
    }

    /** A balance and the version it was read at. */
    private record VersionedBalance(long balance, long version) {}

    /** Account id to balance and version, for the accounts that exist (0, 1 or 2), with no lock. */
    private static Map<Long, VersionedBalance> readAccounts(Connection connection, Transfer transfer)
            throws SQLException {
        Map<Long, VersionedBalance> rows = new LinkedHashMap<>();
        try (PreparedStatement select = connection.prepareStatement(READ_ACCOUNTS)) {
            select.setLong(1, transfer.source().value());
            select.setLong(2, transfer.target().value());
            try (ResultSet result = select.executeQuery()) {
                while (result.next()) {
                    rows.put(result.getLong(1), new VersionedBalance(result.getLong(2), result.getLong(3)));
                }
            }
        }
        return rows;
    }

    /**
     * True when the row still had {@code expectedVersion} and now holds {@code newBalance}. The CHECK
     * (balance >= 0) still backs the funds check: a negative value fails with 23514 and rolls back.
     */
    private static boolean versionedUpdate(Connection connection, AccountId id, long newBalance, long expectedVersion)
            throws SQLException {
        try (PreparedStatement update = connection.prepareStatement(VERSIONED_UPDATE)) {
            update.setLong(1, newBalance);
            update.setLong(2, id.value());
            update.setLong(3, expectedVersion);
            return update.executeUpdate() == 1;
        }
    }

    /**
     * Full jitter: sleeps a uniformly random time from 0 to {@link #backoffCeiling}. The randomness
     * spreads out transfers that collided, so they do not all retry at the same instant and collide
     * again. No minimum is promised, so a plain sleep is right here. Runs with no transaction open.
     * Returns false if interrupted, with the interrupt flag restored.
     */
    private static boolean backOff(int failedAttempts) {
        long ceilingNanos = backoffCeiling(failedAttempts).toNanos();
        try {
            TimeUnit.NANOSECONDS.sleep(ThreadLocalRandom.current().nextLong(ceilingNanos + 1));
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /**
     * The longest back-off after {@code failedAttempts} conflicts: {@code min(cap, base * 2^(n - 1))},
     * so 1, 2, 4, 8, 16, 32, then 50 ms. Nine back-offs at most, so under 0.25 s in total.
     */
    static Duration backoffCeiling(int failedAttempts) {
        if (failedAttempts < 1) {
            throw new IllegalArgumentException("failedAttempts must be at least 1: " + failedAttempts);
        }
        // Capping the shift keeps it far from overflow; the cap is reached long before 20 doublings.
        Duration doubled = BACKOFF_BASE.multipliedBy(1L << Math.min(failedAttempts - 1, 20));
        return doubled.compareTo(BACKOFF_CAP) < 0 ? doubled : BACKOFF_CAP;
    }

    /**
     * How many optimistic attempts so far changed zero rows and were rolled back, retried or not
     * (REQ-DB-004). Always 0 in pessimistic mode.
     */
    public long conflicts() {
        return conflicts.sum();
    }

    /**
     * Test seam: in optimistic mode, {@code hook} runs on every attempt (argument: the attempt number,
     * from 1) on the calling thread, after the unlocked read and before the versioned updates, while
     * the attempt's transaction is open. A test commits a rival write inside it to force a conflict.
     */
    void afterReadHook(IntConsumer hook) {
        this.afterReadHook = Objects.requireNonNull(hook, "hook");
    }

    /**
     * Carries "a versioned update changed zero rows" out of the transaction so that it rolls back. Not
     * an error and never leaves this class, so one shared instance without a stack trace is enough.
     */
    private static final class VersionConflict extends RuntimeException {
        private static final long serialVersionUID = 1L;
        private static final VersionConflict INSTANCE = new VersionConflict();

        private VersionConflict() {
            super("optimistic version conflict", null, false, false);
        }
    }

    private static boolean claim(Connection connection, TransferId id) throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement(CLAIM)) {
            insert.setString(1, id.value());
            return insert.executeUpdate() == 1;
        }
    }

    /**
     * The outcome committed by the transaction that claimed this id first: POSTED with its posting,
     * or a stored REJECTED_FUNDS or REJECTED_INVALID. The claim met a committed row, so the row is
     * visible and holds its final outcome; either check below failing means a bug, never a race.
     */
    private static ApplyResult storedResult(Connection connection, TransferId id) throws SQLException {
        try (PreparedStatement select = connection.prepareStatement(STORED_RESULT)) {
            select.setString(1, id.value());
            try (ResultSet row = select.executeQuery()) {
                if (!row.next()) {
                    throw new IllegalStateException("transfer " + id + " conflicted but no row is visible");
                }
                String stored = row.getString(1);
                if ("PENDING".equals(stored)) {
                    throw new IllegalStateException("transfer " + id + " is visible while PENDING");
                }
                Outcome outcome = Outcome.valueOf(stored);
                if (outcome != Outcome.POSTED) {
                    return ApplyResult.rejected(outcome);
                }
                return ApplyResult.posted(new Posting(
                        row.getLong(2),
                        id,
                        AccountId.of(row.getLong(3)),
                        AccountId.of(row.getLong(4)),
                        Money.of(row.getLong(5))));
            }
        }
    }

    /** Account id to balance, for the accounts that exist (0, 1 or 2 of them), read under the lock. */
    private static Map<Long, Long> lockAccounts(Connection connection, Transfer transfer) throws SQLException {
        Map<Long, Long> balances = new LinkedHashMap<>();
        try (PreparedStatement select = connection.prepareStatement(LOCK_ACCOUNTS)) {
            select.setLong(1, transfer.source().value());
            select.setLong(2, transfer.target().value());
            try (ResultSet rows = select.executeQuery()) {
                while (rows.next()) {
                    balances.put(rows.getLong(1), rows.getLong(2));
                }
            }
        }
        return balances;
    }

    /** Debit and credit in one batch (one round trip); both rows are already locked. */
    private static void moveMoney(Connection connection, AccountId source, AccountId target, long amount)
            throws SQLException {
        try (PreparedStatement update = connection.prepareStatement(MOVE_MONEY)) {
            update.setLong(1, -amount);
            update.setLong(2, source.value());
            update.addBatch();
            update.setLong(1, amount);
            update.setLong(2, target.value());
            update.addBatch();
            update.executeBatch();
        }
    }

    /**
     * The identity column numbers postings in insert order. Two transfers on different accounts can
     * commit in the other order, and a rolled-back insert leaves a gap: order, not "no gaps".
     */
    private static Posting insertPosting(Connection connection, Transfer transfer) throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement(INSERT_POSTING)) {
            insert.setString(1, transfer.id().value());
            insert.setLong(2, transfer.source().value());
            insert.setLong(3, transfer.target().value());
            insert.setLong(4, transfer.amount().minorUnits());
            try (ResultSet key = insert.executeQuery()) {
                key.next();
                return new Posting(
                        key.getLong(1), transfer.id(), transfer.source(), transfer.target(), transfer.amount());
            }
        }
    }

    /** Replaces PENDING on the row this transaction claimed in step 1, so exactly one row changes. */
    private static void recordOutcome(Connection connection, TransferId id, Outcome outcome, Long postingSeq)
            throws SQLException {
        try (PreparedStatement update = connection.prepareStatement(RECORD_OUTCOME)) {
            update.setString(1, outcome.name());
            update.setObject(2, postingSeq, Types.BIGINT);
            update.setString(3, id.value());
            if (update.executeUpdate() != 1) {
                throw new IllegalStateException("transfer " + id + " lost its claim row");
            }
        }
    }

    @Override
    public boolean hasAccount(AccountId id) {
        return balance(id).isPresent();
    }

    /** A plain read: it takes no row lock, so it never makes a transfer wait. */
    @Override
    public Optional<Money> balance(AccountId id) {
        return inTransaction("read balance of " + id, connection -> {
            try (PreparedStatement select = connection.prepareStatement(BALANCE)) {
                select.setLong(1, id.value());
                try (ResultSet row = select.executeQuery()) {
                    return row.next() ? Optional.of(Money.of(row.getLong(1))) : Optional.empty();
                }
            }
        });
    }

    /**
     * Invariant (REQ-DB-010): ONE statement. In READ COMMITTED each statement sees one snapshot of
     * committed data, and a transfer commits both of its balance changes at once, so no transfer is
     * half-visible and the total equals the opening total. Two statements could straddle a commit.
     * It takes no locks, so unlike LockingLedger's snapshot it pauses no transfer.
     */
    @Override
    public Map<AccountId, Money> snapshot() {
        return inTransaction("read snapshot", connection -> {
            Map<AccountId, Money> balances = new LinkedHashMap<>();
            try (PreparedStatement select = connection.prepareStatement(SNAPSHOT);
                    ResultSet rows = select.executeQuery()) {
                while (rows.next()) {
                    balances.put(AccountId.of(rows.getLong(1)), Money.of(rows.getLong(2)));
                }
            }
            return Collections.unmodifiableMap(balances);
        });
    }

    @Override
    public List<Posting> postings() {
        return inTransaction("read postings", connection -> {
            List<Posting> postings = new ArrayList<>();
            try (PreparedStatement select = connection.prepareStatement(POSTINGS);
                    ResultSet rows = select.executeQuery()) {
                while (rows.next()) {
                    postings.add(new Posting(
                            rows.getLong(1),
                            TransferId.of(rows.getString(2)),
                            AccountId.of(rows.getLong(3)),
                            AccountId.of(rows.getLong(4)),
                            Money.of(rows.getLong(5))));
                }
            }
            return List.copyOf(postings);
        });
    }

    /** Work done on one connection inside one transaction. It must never take a second connection. */
    @FunctionalInterface
    private interface SqlWork<T> {
        T run(Connection connection) throws SQLException;
    }

    /**
     * Runs {@code work} in one transaction: settings first, then the work, then COMMIT. Any failure
     * rolls back before the connection goes back to the pool, so no lock outlives the call. The pool restores autocommit
     * when the connection is returned (HikariCP and Spring Boot's pool both do).
     */
    private <T> T inTransaction(String what, SqlWork<T> work) {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                try (Statement settings = connection.createStatement()) {
                    settings.execute(transactionSettings);
                }
                T result = work.run(connection);
                connection.commit();
                return result;
            } catch (SQLException | RuntimeException e) {
                rollback(connection, e);
                throw e;
            }
        } catch (SQLException e) {
            // Also reached when the pool has no free connection within its own timeout.
            throw new LedgerDatabaseException(what + " failed, SQLState " + e.getSQLState(), e);
        }
    }

    private static void rollback(Connection connection, Exception failure) {
        try {
            connection.rollback();
        } catch (SQLException e) {
            failure.addSuppressed(e);
        }
    }
}
