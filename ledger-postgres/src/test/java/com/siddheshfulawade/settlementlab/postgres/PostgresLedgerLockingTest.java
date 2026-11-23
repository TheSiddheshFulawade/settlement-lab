package com.siddheshfulawade.settlementlab.postgres;

import static com.siddheshfulawade.settlementlab.domain.Fixtures.A;
import static com.siddheshfulawade.settlementlab.domain.Fixtures.B;
import static com.siddheshfulawade.settlementlab.domain.Fixtures.transfer;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.siddheshfulawade.settlementlab.domain.AccountId;
import com.siddheshfulawade.settlementlab.domain.Fixtures;
import com.siddheshfulawade.settlementlab.domain.InvariantChecker;
import com.siddheshfulawade.settlementlab.domain.InvariantReport;
import com.siddheshfulawade.settlementlab.domain.Ledger;
import com.siddheshfulawade.settlementlab.domain.Money;
import com.siddheshfulawade.settlementlab.domain.Outcome;
import com.siddheshfulawade.settlementlab.domain.Transfer;
import com.siddheshfulawade.settlementlab.domain.TransferId;
import com.siddheshfulawade.settlementlab.testing.Concurrently;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

/**
 * Lock order and timeouts of the pessimistic PostgresLedger (REQ-DB-003, REQ-DB-009).
 *
 * <p>Implementer contract for timeouts. Constructor:
 * {@code PostgresLedger(DataSource, LockMode, Duration lockTimeout, Duration statementTimeout)}; the
 * two-argument constructor picks defaults (suggested 5 s and 30 s; they must be generous enough for
 * the stress suite). Both limits are applied inside the transaction (for example
 * {@code SET LOCAL lock_timeout = '500ms'}), so they never leak onto a pooled connection.
 *
 * <p>What a timeout returns: it does NOT return an Outcome. {@code apply} throws an unchecked
 * exception (any RuntimeException subclass; the implementer may add a dedicated one) whose cause
 * chain holds the {@link SQLException} with SQLState 55P03 (lock_not_available, from lock_timeout) or
 * 57014 (query_canceled, from statement_timeout). The transaction is rolled back, so no transfer row
 * is left and the same id can be retried. These tests assert exactly that, not the exception class.
 */
class PostgresLedgerLockingTest extends PostgresTestBase {

    private static final String LOCK_NOT_AVAILABLE = "55P03";
    private static final String DEADLOCK_DETECTED = "40P01";
    private static final String QUERY_CANCELED = "57014";
    private static final Duration NEVER = Duration.ofSeconds(30);

    private Ledger ledger(Map<AccountId, Money> opening) {
        LedgerSeed.reset(dataSource(), opening);
        return new PostgresLedger(dataSource(), LockMode.PESSIMISTIC);
    }

    // ---- REQ-DB-003: ascending lock order, no deadlock ------------------------------------------

    @Test
    void REQ_DB_003_opposing_transfers_A_to_B_and_B_to_A_never_deadlock() throws Exception {
        Map<AccountId, Money> opening = Map.of(A, Money.of(1_000), B, Money.of(1_000));
        Ledger ledger = ledger(opening);
        Set<TransferId> accepted = ConcurrentHashMap.newKeySet();

        // A deadlock would surface as SQLState 40P01 thrown from apply, which Concurrently reports as
        // a worker failure; a hang would fail on the timeout.
        Concurrently.run(6, Duration.ofMinutes(3), thread -> {
            for (int i = 0; i < 300; i++) {
                Transfer t = thread % 2 == 0
                        ? transfer("ab-" + thread + "-" + i, A, B, 1)
                        : transfer("ba-" + thread + "-" + i, B, A, 1);
                if (ledger.apply(t) == Outcome.POSTED) {
                    accepted.add(t.id());
                }
            }
        });

        assertThat(accepted).as("every transfer fits, so every one posts").hasSize(6 * 300);
        assertInvariants(opening, ledger, accepted);
    }

    @Test
    void REQ_DB_003_a_ring_of_transfers_1_to_2_to_3_and_back_to_1_never_deadlocks() throws Exception {
        int accounts = 6;
        Map<AccountId, Money> opening = Fixtures.uniformAccounts(accounts, 1_000);
        Ledger ledger = ledger(opening);
        Set<TransferId> accepted = ConcurrentHashMap.newKeySet();

        Concurrently.run(accounts, Duration.ofMinutes(3), thread -> {
            AccountId source = AccountId.of(thread + 1);
            AccountId target = AccountId.of((thread + 1) % accounts + 1);
            for (int i = 0; i < 300; i++) {
                Transfer t = transfer("ring-" + thread + "-" + i, source, target, 1);
                if (ledger.apply(t) == Outcome.POSTED) {
                    accepted.add(t.id());
                }
            }
        });

        assertThat(accepted).hasSize(accounts * 300);
        assertInvariants(opening, ledger, accepted);
    }

    @Test
    void REQ_DB_003_the_lower_account_id_is_locked_first_even_when_it_is_the_target() throws Exception {
        // Transfer 2 -> 1 (source id is the HIGHER one). Another connection holds row 1. A ledger that
        // locks in ascending order waits on row 1 without having locked row 2; one that locks the
        // source first would already hold row 2, and NOWAIT on row 2 below would fail with 55P03.
        Ledger ledger = new PostgresLedger(seeded(), LockMode.PESSIMISTIC, NEVER, NEVER);

        try (Connection holder = dataSource().getConnection()) {
            lockRow(holder, 1);
            CompletableFuture<Outcome> pending =
                    CompletableFuture.supplyAsync(() -> ledger.apply(transfer("desc", B, A, 5)));

            awaitSomeoneWaitingForARowLock();
            try (Connection probe = dataSource().getConnection()) {
                probe.setAutoCommit(false);
                try (Statement s = probe.createStatement()) {
                    s.execute("SELECT id FROM account WHERE id = 2 FOR UPDATE NOWAIT");
                }
                probe.rollback();
            }
            holder.rollback();

            await().atMost(Duration.ofSeconds(20)).until(pending::isDone);
            assertThat(pending.get()).isEqualTo(Outcome.POSTED);
        }
    }

    // ---- REQ-DB-009: bounded waits --------------------------------------------------------------

    @Test
    void REQ_DB_009_a_lock_wait_longer_than_lock_timeout_fails_and_leaves_nothing_behind() throws Exception {
        Duration lockTimeout = Duration.ofMillis(500);
        Ledger ledger = new PostgresLedger(seeded(), LockMode.PESSIMISTIC, lockTimeout, NEVER);
        Transfer blocked = transfer("blocked", A, B, 10);

        try (Connection holder = dataSource().getConnection()) {
            lockRow(holder, 1);
            long start = System.nanoTime();
            CompletableFuture<Outcome> pending = CompletableFuture.supplyAsync(() -> ledger.apply(blocked));

            // Must return (by throwing) instead of waiting for the holder, which never lets go here.
            await().atMost(Duration.ofSeconds(15)).until(pending::isDone);
            Duration waited = Duration.ofNanos(System.nanoTime() - start);

            assertThat(pending.isCompletedExceptionally()).isTrue();
            assertThat(sqlStates(failureOf(pending))).contains(LOCK_NOT_AVAILABLE);
            // lock_timeout promises a minimum wait; allow some slack for timer granularity.
            assertThat(waited).isGreaterThanOrEqualTo(Duration.ofMillis(400));
            holder.rollback();
        }

        assertThat(count("transfer"))
                .as("the rolled-back transaction left no transfer row")
                .isZero();
        assertThat(count("posting")).isZero();
        assertThat(ledger.balance(A)).contains(Money.of(100));
        assertThat(ledger.balance(B)).contains(Money.of(100));
        assertThat(ledger.apply(blocked))
                .as("same id retried once the lock is free")
                .isEqualTo(Outcome.POSTED);
    }

    @Test
    void REQ_DB_009_a_statement_running_longer_than_statement_timeout_is_cancelled() throws Exception {
        // Lock wait is allowed 30 s, the statement only 300 ms, so statement_timeout fires first
        // (it also covers time spent waiting for a lock).
        Ledger ledger = new PostgresLedger(seeded(), LockMode.PESSIMISTIC, NEVER, Duration.ofMillis(300));

        try (Connection holder = dataSource().getConnection()) {
            lockRow(holder, 1);
            CompletableFuture<Outcome> pending =
                    CompletableFuture.supplyAsync(() -> ledger.apply(transfer("slow", A, B, 10)));

            await().atMost(Duration.ofSeconds(15)).until(pending::isDone);

            assertThat(pending.isCompletedExceptionally()).isTrue();
            assertThat(sqlStates(failureOf(pending))).contains(QUERY_CANCELED);
            holder.rollback();
        }
        assertThat(count("transfer")).isZero();
    }

    @Test
    void REQ_DB_009_the_timeouts_do_not_stay_on_the_pooled_connection() throws Exception {
        Ledger ledger =
                new PostgresLedger(seeded(), LockMode.PESSIMISTIC, Duration.ofMillis(777), Duration.ofSeconds(9));
        ledger.apply(transfer("warm", A, B, 1));

        // The pool has handed the ledger's connection back; whoever borrows it next (the outbox relay,
        // another feature) must not inherit a 777 ms lock timeout.
        try (Connection c = dataSource().getConnection();
                Statement s = c.createStatement();
                ResultSet rs = s.executeQuery("SHOW lock_timeout")) {
            rs.next();
            assertThat(rs.getString(1)).isNotEqualTo("777ms");
        }
    }

    // TODO-ME: write the mirror of REQ_DB_009_a_lock_wait_longer_than_lock_timeout...: hold the row
    // of the TARGET account (id 2) instead of the source, run the transfer 1 -> 2 on a ledger with a
    // 500 ms lock timeout, and assert it fails with SQLState 55P03 and that account 1 still shows
    // balance 100 and the transfer table is empty. Check it: temporarily make PostgresLedger skip the
    // lock on the higher id; the test must then fail (no timeout, the transfer posts); restore it.
    @Test
    @Disabled("TODO-ME: left for the user to write")
    void REQ_DB_009_a_locked_target_row_times_out_too() {}

    // ---- helpers --------------------------------------------------------------------------------

    /** Two accounts, ids 1 and 2, 100 each; returns the pool. */
    private javax.sql.DataSource seeded() {
        LedgerSeed.reset(dataSource(), Map.of(A, Money.of(100), B, Money.of(100)));
        return dataSource();
    }

    private static void lockRow(Connection connection, long id) throws SQLException {
        connection.setAutoCommit(false);
        try (Statement s = connection.createStatement()) {
            s.execute("SELECT id FROM account WHERE id = " + id + " FOR UPDATE");
        }
    }

    /** Waits until some backend is blocked on a row lock, i.e. the transfer reached its first lock. */
    private void awaitSomeoneWaitingForARowLock() {
        await().atMost(Duration.ofSeconds(15)).until(() -> {
            try (Connection c = dataSource().getConnection();
                    Statement s = c.createStatement();
                    ResultSet rs = s.executeQuery("SELECT count(*) FROM pg_stat_activity"
                            + " WHERE wait_event_type = 'Lock' AND datname = current_database()")) {
                rs.next();
                return rs.getLong(1) > 0;
            }
        });
    }

    private long count(String table) throws SQLException {
        try (Connection c = dataSource().getConnection();
                Statement s = c.createStatement();
                ResultSet rs = s.executeQuery("SELECT count(*) FROM " + table)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private static Throwable failureOf(CompletableFuture<?> future) {
        try {
            future.get();
            throw new AssertionError("expected the future to fail");
        } catch (ExecutionException e) {
            return e.getCause();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }

    /** SQLStates found anywhere in the cause chain. */
    private static List<String> sqlStates(Throwable failure) {
        List<String> states = new ArrayList<>();
        for (Throwable t = failure; t != null; t = t.getCause()) {
            if (t instanceof SQLException e && e.getSQLState() != null) {
                states.add(e.getSQLState());
            }
        }
        assertThat(states).as("no deadlock may be involved in a timeout").doesNotContain(DEADLOCK_DETECTED);
        return states;
    }

    private static void assertInvariants(Map<AccountId, Money> opening, Ledger ledger, Set<TransferId> accepted) {
        InvariantReport report = InvariantChecker.checkLedger(opening, ledger.snapshot(), ledger.postings(), accepted);
        assertThat(report.allPassed()).as(report.toString()).isTrue();
    }
}
