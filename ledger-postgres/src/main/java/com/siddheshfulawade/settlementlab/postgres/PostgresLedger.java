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
import javax.sql.DataSource;

/**
 * A {@link Ledger} whose balances, transfers and postings live in PostgreSQL (SPEC.md 16.3), with
 * plain JDBC so every statement and lock is visible.
 *
 * <p><b>Isolation: READ COMMITTED</b>, set explicitly on every transaction so a pool configured
 * otherwise cannot change the behaviour. It is enough because correctness does not rest on what a
 * snapshot shows: the row locks serialise every writer of an account, and {@code SELECT ... FOR
 * UPDATE} returns the latest committed balance after it waits. SERIALIZABLE would add 40001
 * retries for no extra safety. Oracle: READ COMMITTED is its default, with the same row-lock rules.
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

    private static final String INSERT_POSTING =
            "INSERT INTO posting (transfer_id, source, target, amount) VALUES (?, ?, ?, ?) RETURNING seq";

    private static final String RECORD_OUTCOME = "UPDATE transfer SET outcome = ?, posting_seq = ? WHERE id = ?";

    private static final String STORED_RESULT = "SELECT t.outcome, p.seq, p.source, p.target, p.amount"
            + " FROM transfer t LEFT JOIN posting p ON p.seq = t.posting_seq WHERE t.id = ?";

    private static final String SNAPSHOT = "SELECT id, balance FROM account ORDER BY id";
    private static final String BALANCE = "SELECT balance FROM account WHERE id = ?";
    private static final String POSTINGS = "SELECT seq, transfer_id, source, target, amount FROM posting ORDER BY seq";

    private final DataSource dataSource;

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
        Objects.requireNonNull(mode, "mode");
        if (mode == LockMode.OPTIMISTIC) {
            throw new UnsupportedOperationException("optimistic mode arrives in V3-05");
        }
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
     * One transaction, SPEC.md 16.3. Every outcome that reaches the database is stored on the id's
     * {@code transfer} row and replayed for the same id (REQ-DB-005): POSTED, REJECTED_FUNDS, and
     * REJECTED_INVALID for an unknown account. A lock or statement timeout, or any other error, rolls
     * everything back, the claim included, and throws {@link LedgerDatabaseException}; the id stays
     * free, so the same id can then be retried.
     */
    @Override
    public ApplyResult applyWithResult(Transfer transfer) {
        // Amount and same-account rules need no database; account existence is checked by step 2.
        // These are rejected before any database call, so nothing is stored for the id. That is safe:
        // the answer depends only on the transfer itself, so a replay gets the same answer anyway.
        if (TransferValidator.validate(transfer, id -> true).isPresent()) {
            return ApplyResult.rejected(Outcome.REJECTED_INVALID);
        }
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
