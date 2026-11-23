package com.siddheshfulawade.settlementlab.postgres;

import static com.siddheshfulawade.settlementlab.domain.Fixtures.A;
import static com.siddheshfulawade.settlementlab.domain.Fixtures.B;
import static com.siddheshfulawade.settlementlab.domain.Fixtures.transfer;
import static org.assertj.core.api.Assertions.assertThat;

import com.siddheshfulawade.settlementlab.domain.AccountId;
import com.siddheshfulawade.settlementlab.domain.InvariantChecker;
import com.siddheshfulawade.settlementlab.domain.InvariantReport;
import com.siddheshfulawade.settlementlab.domain.Money;
import com.siddheshfulawade.settlementlab.domain.Outcome;
import com.siddheshfulawade.settlementlab.domain.Transfer;
import com.siddheshfulawade.settlementlab.domain.TransferId;
import com.siddheshfulawade.settlementlab.testing.Concurrently;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

/**
 * Optimistic mode of PostgresLedger (REQ-DB-004, REQ-DB-004a).
 *
 * <p>Implementer contract (names the tests rely on):
 *
 * <ul>
 *   <li>{@code PostgresLedger#conflicts()} returns a {@code long}: how many attempts so far updated
 *       zero rows and were rolled back (every failed attempt counts, retried or not). 0 in
 *       pessimistic mode.
 *   <li>package-private {@code PostgresLedger#afterReadHook(java.util.function.IntConsumer)}: in
 *       optimistic mode the ledger calls the hook on every attempt (argument: attempt number from 1),
 *       after it read balances and versions and BEFORE it issues the versioned updates, on the same
 *       thread, while its transaction is open. Default: does nothing. The optimistic read takes no row
 *       lock, so a second connection can commit inside the hook without blocking.
 *   <li>{@code Outcome.REJECTED_CONTENTION} after exactly 10 attempts (REQ-DB-004a); the transaction
 *       is rolled back, so no transfer row, posting or outbox row exists for that id.
 *   <li>back-off between attempts is a bounded random sleep: ten attempts take well under 30 s.
 * </ul>
 */
class PostgresLedgerOptimisticTest extends PostgresTestBase {

    private static final Duration LIMIT = Duration.ofMinutes(2);

    private PostgresLedger ledger(Map<AccountId, Money> opening) {
        LedgerSeed.reset(dataSource(), opening);
        return new PostgresLedger(dataSource(), LockMode.OPTIMISTIC);
    }

    private long version(long account) throws SQLException {
        try (Connection c = dataSource().getConnection();
                PreparedStatement s = c.prepareStatement("SELECT version FROM account WHERE id = ?")) {
            s.setLong(1, account);
            try (ResultSet r = s.executeQuery()) {
                r.next();
                return r.getLong(1);
            }
        }
    }

    private long count(String table, String where) throws SQLException {
        try (Connection c = dataSource().getConnection();
                PreparedStatement s = c.prepareStatement("SELECT count(*) FROM " + table + " WHERE " + where);
                ResultSet r = s.executeQuery()) {
            r.next();
            return r.getLong(1);
        }
    }

    /** Commits a version bump on one account from a second connection, as a rival writer would. */
    private void bumpVersion(long account) {
        try (Connection c = dataSource().getConnection();
                PreparedStatement s = c.prepareStatement("UPDATE account SET version = version + 1 WHERE id = ?")) {
            s.setLong(1, account);
            s.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void REQ_DB_004_no_lost_update_when_many_threads_hammer_two_hot_accounts() throws Exception {
        Map<AccountId, Money> opening = Map.of(A, Money.of(1_000_000), B, Money.of(1_000_000));
        PostgresLedger ledger = ledger(opening);
        Set<TransferId> posted = ConcurrentHashMap.newKeySet();

        Concurrently.run(8, LIMIT, thread -> {
            for (int i = 0; i < 100; i++) {
                Transfer t = thread % 2 == 0
                        ? transfer("ab-" + thread + "-" + i, A, B, 1)
                        : transfer("ba-" + thread + "-" + i, B, A, 1);
                // Every transfer fits, so the only legal answers are POSTED and (after 10 conflicts) contention.
                Outcome outcome = ledger.apply(t);
                assertThat(outcome).isIn(Outcome.POSTED, Outcome.REJECTED_CONTENTION);
                if (outcome == Outcome.POSTED) {
                    posted.add(t.id());
                }
            }
        });

        InvariantReport report = InvariantChecker.checkLedger(opening, ledger.snapshot(), ledger.postings(), posted);
        assertThat(report.allPassed()).as(report.toString()).isTrue();
        assertThat(ledger.postings()).hasSameSizeAs(posted);
        assertThat(ledger.conflicts())
                .as("8 threads on 2 rows must collide at least once")
                .isPositive();
    }

    @Test
    void REQ_DB_004_a_conflicting_attempt_rolls_back_retries_and_then_posts_once() {
        PostgresLedger ledger = ledger(Map.of(A, Money.of(100), B, Money.of(100)));
        ledger.afterReadHook(attempt -> {
            if (attempt <= 3) { // three rivals commit in the window; the fourth attempt is clean
                bumpVersion(1);
            }
        });

        Outcome outcome = ledger.apply(transfer("t-1", A, B, 30));

        assertThat(outcome).isEqualTo(Outcome.POSTED);
        assertThat(ledger.conflicts()).isEqualTo(3);
        assertThat(ledger.postings()).hasSize(1);
        assertThat(ledger.balance(A)).contains(Money.of(70));
        assertThat(ledger.balance(B)).contains(Money.of(130));
    }

    @Test
    void REQ_DB_004a_after_ten_conflicting_attempts_it_answers_contention_and_stores_nothing() throws Exception {
        PostgresLedger ledger = ledger(Map.of(A, Money.of(100), B, Money.of(100)));
        AtomicInteger attempts = new AtomicInteger();
        ledger.afterReadHook(attempt -> {
            attempts.incrementAndGet();
            bumpVersion(1); // a rival commits on every attempt, so every attempt conflicts
        });

        Outcome outcome = ledger.apply(transfer("hot", A, B, 30));

        assertThat(outcome).isEqualTo(Outcome.REJECTED_CONTENTION);
        assertThat(attempts).as("exactly 10 attempts (REQ-DB-004a)").hasValue(10);
        assertThat(ledger.conflicts()).isEqualTo(10);
        assertThat(count("transfer", "id = 'hot'"))
                .as("no transfer row: the id stays free")
                .isZero();
        assertThat(count("posting", "transfer_id = 'hot'")).isZero();
        assertThat(count("outbox", "true")).isZero();
        assertThat(ledger.balance(A)).contains(Money.of(100));
        assertThat(ledger.balance(B)).contains(Money.of(100));
    }

    @Test
    void REQ_DB_004a_the_same_id_is_processed_fresh_after_contention() {
        PostgresLedger ledger = ledger(Map.of(A, Money.of(100), B, Money.of(100)));
        ledger.afterReadHook(attempt -> bumpVersion(1));
        assertThat(ledger.apply(transfer("again", A, B, 30))).isEqualTo(Outcome.REJECTED_CONTENTION);

        ledger.afterReadHook(attempt -> {});
        Outcome retry = ledger.apply(transfer("again", A, B, 30));

        assertThat(retry).isEqualTo(Outcome.POSTED);
        assertThat(ledger.postings()).hasSize(1);
    }

    @Test
    void REQ_DB_004_each_posting_raises_the_version_of_both_touched_accounts_by_exactly_one() throws Exception {
        PostgresLedger ledger = ledger(Map.of(A, Money.of(100), B, Money.of(100), AccountId.of(3), Money.of(100)));

        for (int i = 0; i < 5; i++) {
            assertThat(ledger.apply(transfer("v-" + i, A, B, 1))).isEqualTo(Outcome.POSTED);
        }

        assertThat(version(1)).isEqualTo(5);
        assertThat(version(2)).isEqualTo(5);
        assertThat(version(3)).as("an untouched account keeps its version").isZero();
    }

    @Test
    void REQ_DB_004_a_rejection_for_funds_changes_no_version() throws Exception {
        PostgresLedger ledger = ledger(Map.of(A, Money.of(10), B, Money.of(10)));

        assertThat(ledger.apply(transfer("poor", A, B, 50))).isEqualTo(Outcome.REJECTED_FUNDS);

        assertThat(version(1)).isZero();
        assertThat(version(2)).isZero();
    }

    @Test
    void REQ_DB_005_the_same_id_applied_twice_posts_once_in_optimistic_mode() {
        PostgresLedger ledger = ledger(Map.of(A, Money.of(100), B, Money.of(100)));

        Outcome first = ledger.apply(transfer("dup", A, B, 10));
        Outcome second = ledger.apply(transfer("dup", A, B, 10));

        assertThat(first).isEqualTo(Outcome.POSTED);
        assertThat(second).isEqualTo(Outcome.POSTED);
        assertThat(ledger.postings()).hasSize(1);
    }

    @Test
    void REQ_DB_004_ten_conflicting_attempts_finish_within_a_generous_bound_so_the_backoff_is_bounded() {
        PostgresLedger ledger = ledger(Map.of(A, Money.of(100), B, Money.of(100)));
        ledger.afterReadHook(attempt -> bumpVersion(1));

        long start = System.nanoTime();
        ledger.apply(transfer("slow", A, B, 1));
        Duration took = Duration.ofNanos(System.nanoTime() - start);

        assertThat(took).isLessThan(Duration.ofSeconds(30)); // upper bound only; no minimum is promised
    }

    // TODO-ME: prove the back-off has jitter. Write `two_ledgers_retrying_the_same_conflict_do_not_sleep_
    //  identical_delays`: build two PostgresLedger instances over the same seeded data, make each hit
    //  10 conflicts through afterReadHook, and record the time between consecutive hook calls (the
    //  back-off) for each. Assert the two delay lists are not equal (loose tolerance, never an exact
    //  value). Check it: it passes; then replace the random delay in PostgresLedger with a constant
    //  and see it fail.
    @Disabled("TODO-ME: left for the user")
    @Test
    void two_ledgers_retrying_the_same_conflict_do_not_sleep_identical_delays() {}
}
