package com.siddheshfulawade.settlementlab.postgres;

import com.siddheshfulawade.settlementlab.domain.AccountId;
import com.siddheshfulawade.settlementlab.domain.Money;
import com.siddheshfulawade.settlementlab.domain.Posting;
import com.siddheshfulawade.settlementlab.domain.TransferId;
import com.siddheshfulawade.settlementlab.gl.GeneralLedgerPort;
import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;

/**
 * Moves postings from the database outbox to the general ledger (REQ-DB-007, SPEC.md 16.4).
 *
 * <p>One pass ({@link #relayOnce}) has three steps, and only the first is a transaction that locks:
 *
 * <ol>
 *   <li><b>Claim</b>, one short transaction: lock up to {@code batchSize} due rows with {@code FOR
 *       UPDATE SKIP LOCKED}, push their {@code next_attempt_at} forward by the lease, COMMIT. The
 *       lease, not the row lock, is the claim from here on: the lock ends at COMMIT.
 *   <li><b>Publish</b> each claimed row, lowest sequence first, with NO connection held and no
 *       transaction open (REQ-DB-009): a slow or failing GL never pins a pool connection or a lock.
 *   <li><b>Settle</b> each row in its own one-statement transaction: delete it once the GL accepted
 *       it, or count the attempt and push it back by the lease if the publish threw.
 * </ol>
 *
 * <p><b>Invariant: two relays never publish a row while its lease is live.</b> A second relay's claim
 * meets one of three states. (a) The first relay's claim transaction is still open: the row is
 * locked and SKIP LOCKED passes over it, without waiting. (b) That transaction committed: the row now
 * says {@code next_attempt_at = now() + lease}, in the future, so the {@code WHERE} does not select it;
 * and if the second relay's scan began before that commit, PostgreSQL re-checks the {@code WHERE}
 * against the newly committed row version before it locks it (READ COMMITTED's re-check of a
 * concurrently updated row), and the row is skipped. (c) It rolled back: nothing was claimed, so
 * taking the row is correct. Oracle: the same {@code FOR UPDATE SKIP LOCKED}, with the same re-check.
 *
 * <p><b>At-least-once delivery, exactly-once effect.</b> A row is deleted only after the GL accepted
 * it, so a crash, a lost ack or a lease that ran out before the delete means the row is sent again
 * once its lease expires, by this relay or another one, after a restart too: the outbox lives in the
 * database, not in the process. That re-send is harmless because the GL is keyed by transfer id: it
 * answers {@code ALREADY_RECORDED} and stores nothing. So choose a lease longer than the slowest
 * batch (retries inside the port included); a shorter one only costs duplicate calls, never money.
 *
 * <p><b>Isolation: READ COMMITTED</b>, set on every transaction, like {@link PostgresLedger}. The
 * claim is safe because of the row locks and the re-check above, not because of a snapshot; nothing
 * here needs SERIALIZABLE.
 *
 * <p>Holds no mutable state: every claim lives in the database, so any number of relays (threads or
 * processes, one instance or several) may run against one database at once.
 */
public final class OutboxRelay {

    /**
     * Claim, step 1: the oldest due rows with their postings. {@code FOR UPDATE OF o} locks only the
     * outbox rows; the posting rows stay unlocked (nothing ever changes them). {@code SKIP LOCKED}
     * passes over rows another relay is claiming right now instead of queueing behind it.
     *
     * <p>Oracle: {@code FOR UPDATE OF o.posting_seq SKIP LOCKED}, but Oracle does not allow {@code
     * FETCH FIRST n ROWS} with {@code FOR UPDATE} (ORA-02014), and ROWNUM is applied before rows are
     * skipped. The Oracle pattern is to open the cursor without a limit and fetch only n rows: with
     * SKIP LOCKED, Oracle locks a row when it is fetched, not when the cursor opens.
     */
    private static final String CLAIM = "SELECT o.posting_seq, p.transfer_id, p.source, p.target, p.amount"
            + " FROM outbox o JOIN posting p ON p.seq = o.posting_seq"
            + " WHERE o.next_attempt_at <= now()"
            + " ORDER BY o.posting_seq LIMIT ?"
            + " FOR UPDATE OF o SKIP LOCKED";

    /**
     * Claim, step 2: the lease. The rows are already locked by this transaction, so this never waits.
     * Oracle: {@code SYSTIMESTAMP + NUMTODSINTERVAL(?, 'SECOND')}, and a collection or IN list for ANY.
     */
    private static final String LEASE =
            "UPDATE outbox SET next_attempt_at = now() + ? * interval '1 millisecond' WHERE posting_seq = ANY(?)";

    /** Accepted by the GL: the message is done. Zero rows if another relay already deleted it. */
    private static final String DELETE = "DELETE FROM outbox WHERE posting_seq = ?";

    /** The publish threw: count it and try again once a lease has passed. */
    private static final String BACK_OFF = "UPDATE outbox SET attempts = attempts + 1,"
            + " next_attempt_at = now() + ? * interval '1 millisecond' WHERE posting_seq = ?";

    private static final String REMAINING = "SELECT count(*) FROM outbox";

    /** How long {@link #drain} waits before polling again when no row is due. Short, but not a spin. */
    static final Duration IDLE_POLL = Duration.ofMillis(20);

    private final DataSource dataSource;
    private final GeneralLedgerPort generalLedger;
    private final int batchSize;
    private final long leaseMillis;
    private final String transactionSettings;

    /**
     * @param generalLedger usually a {@code ResilientPublisher}, so retries and the circuit breaker
     *     run inside one publish; the lease must outlast that
     * @param batchSize the most rows one pass claims
     * @param lease how long a claim stays live, and how long a failed row waits before its next try
     */
    public OutboxRelay(DataSource dataSource, GeneralLedgerPort generalLedger, int batchSize, Duration lease) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.generalLedger = Objects.requireNonNull(generalLedger, "generalLedger");
        if (batchSize < 1) {
            throw new IllegalArgumentException("batchSize must be at least 1: " + batchSize);
        }
        this.batchSize = batchSize;
        Objects.requireNonNull(lease, "lease");
        // A zero lease would let a second relay claim a row the moment the first one commits.
        if (lease.compareTo(Duration.ofMillis(1)) < 0) {
            throw new IllegalArgumentException("lease must be at least 1 ms: " + lease);
        }
        this.leaseMillis = lease.toMillis();
        // The claim never waits for an outbox row lock (SKIP LOCKED); a settle may wait briefly for a
        // claim transaction of another relay. Both are bounded, as in PostgresLedger (REQ-DB-009).
        this.transactionSettings = "SET TRANSACTION ISOLATION LEVEL READ COMMITTED;"
                + " SET LOCAL lock_timeout = '" + PostgresLedger.DEFAULT_LOCK_TIMEOUT.toMillis() + "ms';"
                + " SET LOCAL statement_timeout = '" + PostgresLedger.DEFAULT_STATEMENT_TIMEOUT.toMillis() + "ms'";
    }

    /**
     * One pass: claim, publish each claimed row in sequence order, settle each one. A publish that
     * throws any RuntimeException (GL error, timeout, outage, open circuit) leaves its row in the
     * outbox for a later pass and does not stop the others. Returns how many rows the GL accepted
     * ({@code RECORDED} or {@code ALREADY_RECORDED}). A database failure throws {@link
     * LedgerDatabaseException}; the rows it did not settle are re-sent after their lease, harmlessly.
     */
    public int relayOnce() {
        List<Posting> claimed = claim();
        int accepted = 0;
        for (Posting posting : claimed) {
            // No connection is held and no transaction is open here (REQ-DB-009): the claim has
            // committed and handed its connection back, and each settle below takes its own.
            if (publish(posting)) {
                settle("delete outbox row " + posting.sequence(), DELETE, posting.sequence(), false);
                accepted++;
            } else {
                settle("back off outbox row " + posting.sequence(), BACK_OFF, posting.sequence(), true);
            }
        }
        return accepted;
    }

    private boolean publish(Posting posting) {
        try {
            generalLedger.publish(posting); // either Ack means the GL holds the entry now
            return true;
        } catch (RuntimeException failed) {
            // Kept, not rethrown: the row stays in the outbox, which is the retry. The port tells us
            // nothing reliable about whether the GL stored it (lost ack), and it does not matter.
            return false;
        }
    }

    /** The claim transaction: lock, lease, COMMIT. Returns the claimed postings in sequence order. */
    private List<Posting> claim() {
        return inTransaction("claim outbox rows", connection -> {
            List<Posting> claimed = new ArrayList<>();
            try (PreparedStatement select = connection.prepareStatement(CLAIM)) {
                select.setInt(1, batchSize);
                try (ResultSet rows = select.executeQuery()) {
                    while (rows.next()) {
                        claimed.add(new Posting(
                                rows.getLong(1),
                                TransferId.of(rows.getString(2)),
                                AccountId.of(rows.getLong(3)),
                                AccountId.of(rows.getLong(4)),
                                Money.of(rows.getLong(5))));
                    }
                }
            }
            if (claimed.isEmpty()) {
                return claimed;
            }
            Long[] sequences = claimed.stream().map(Posting::sequence).toArray(Long[]::new);
            Array keys = connection.createArrayOf("bigint", sequences);
            try (PreparedStatement lease = connection.prepareStatement(LEASE)) {
                lease.setLong(1, leaseMillis);
                lease.setArray(2, keys);
                lease.executeUpdate();
            } finally {
                keys.free();
            }
            return claimed;
        });
    }

    /** One statement on one row, in its own short transaction. */
    private void settle(String what, String sql, long postingSeq, boolean withLease) {
        inTransaction(what, connection -> {
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                int index = 1;
                if (withLease) {
                    statement.setLong(index++, leaseMillis);
                }
                statement.setLong(index, postingSeq);
                return statement.executeUpdate();
            }
        });
    }

    /**
     * Relays until the outbox is empty; false if {@code timeout} passed first (or the thread was
     * interrupted). Rows that are not yet due (leased or backed off) are waited for: when a pass
     * accepts nothing, it waits {@link #IDLE_POLL} before the next one, with no connection held.
     */
    public boolean drain(Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (remaining() > 0) {
            int accepted = relayOnce();
            long left = deadline - System.nanoTime();
            if (left <= 0) {
                return remaining() == 0;
            }
            if (accepted == 0 && !pause(Math.min(left, IDLE_POLL.toNanos()))) {
                return false;
            }
        }
        return true;
    }

    /** Rows still in the outbox, due or not. */
    long remaining() {
        return inTransaction("count outbox rows", connection -> {
            try (PreparedStatement count = connection.prepareStatement(REMAINING);
                    ResultSet row = count.executeQuery()) {
                row.next();
                return row.getLong(1);
            }
        });
    }

    /** No minimum is promised, so a plain sleep is right. False if interrupted, flag restored. */
    private static boolean pause(long nanos) {
        try {
            TimeUnit.NANOSECONDS.sleep(nanos);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    @FunctionalInterface
    private interface SqlWork<T> {
        T run(Connection connection) throws SQLException;
    }

    /**
     * Runs {@code work} in one short transaction on one borrowed connection, handed back before this
     * returns. Any failure rolls back first, so no lock or claim transaction outlives the call.
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
                try {
                    connection.rollback();
                } catch (SQLException rollbackFailed) {
                    e.addSuppressed(rollbackFailed);
                }
                throw e;
            }
        } catch (SQLException e) {
            throw new LedgerDatabaseException(what + " failed, SQLState " + e.getSQLState(), e);
        }
    }
}
