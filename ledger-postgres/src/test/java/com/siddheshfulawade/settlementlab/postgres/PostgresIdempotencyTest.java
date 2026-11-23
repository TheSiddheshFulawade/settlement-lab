package com.siddheshfulawade.settlementlab.postgres;

import static com.siddheshfulawade.settlementlab.domain.Fixtures.A;
import static com.siddheshfulawade.settlementlab.domain.Fixtures.B;
import static com.siddheshfulawade.settlementlab.domain.Fixtures.C;
import static com.siddheshfulawade.settlementlab.domain.Fixtures.transfer;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.siddheshfulawade.settlementlab.domain.AccountId;
import com.siddheshfulawade.settlementlab.domain.ApplyResult;
import com.siddheshfulawade.settlementlab.domain.Fixtures;
import com.siddheshfulawade.settlementlab.domain.InvariantChecker;
import com.siddheshfulawade.settlementlab.domain.InvariantReport;
import com.siddheshfulawade.settlementlab.domain.Money;
import com.siddheshfulawade.settlementlab.domain.Outcome;
import com.siddheshfulawade.settlementlab.domain.TransferId;
import com.siddheshfulawade.settlementlab.testing.Concurrently;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

/**
 * Idempotency by the {@code transfer} key (REQ-DB-005, SPEC.md 16.3 step 1, 16.5): the same transfer
 * id is applied at most once and always gets the original answer, whichever thread, instance or
 * process lifetime asks. A rejection is stored too: same id, same answer; a new attempt needs a new id.
 */
class PostgresIdempotencyTest extends PostgresTestBase {

    private PostgresLedger ledgerWith(Map<AccountId, Money> opening) {
        LedgerSeed.reset(dataSource(), opening);
        return new PostgresLedger(dataSource(), LockMode.PESSIMISTIC);
    }

    @Test
    void REQ_DB_005_the_same_id_applied_twice_returns_the_same_posting_and_changes_balances_once() throws SQLException {
        PostgresLedger ledger = ledgerWith(Map.of(A, Money.of(100), B, Money.of(50)));

        ApplyResult first = ledger.applyWithResult(transfer("dup", A, B, 30));
        ApplyResult second = ledger.applyWithResult(transfer("dup", A, B, 30));

        assertThat(first.outcome()).isEqualTo(Outcome.POSTED);
        assertThat(second).as("same outcome, same posting sequence and amount").isEqualTo(first);
        assertThat(ledger.balance(A)).contains(Money.of(70));
        assertThat(ledger.balance(B)).contains(Money.of(80));
        assertThat(count("transfer")).isEqualTo(1);
        assertThat(count("posting")).isEqualTo(1);
    }

    @Test
    void REQ_DB_005_fifty_threads_applying_the_same_id_post_it_once_and_all_get_the_same_answer() throws Exception {
        PostgresLedger ledger = ledgerWith(Map.of(A, Money.of(100), B, Money.of(50)));
        List<ApplyResult> results = new CopyOnWriteArrayList<>();

        Concurrently.run(
                50, Duration.ofMinutes(2), thread -> results.add(ledger.applyWithResult(transfer("one", A, B, 30))));

        assertThat(results).hasSize(50);
        assertThat(Set.copyOf(results))
                .as("every caller sees the identical result")
                .hasSize(1);
        assertThat(results.get(0).outcome()).isEqualTo(Outcome.POSTED);
        assertThat(ledger.balance(A)).contains(Money.of(70));
        assertThat(ledger.balance(B)).contains(Money.of(80));
        assertThat(count("posting")).isEqualTo(1);
        assertThat(count("transfer")).isEqualTo(1);
    }

    @Test
    void REQ_DB_005_two_ledger_instances_on_one_database_post_each_id_at_most_once() throws Exception {
        Map<AccountId, Money> opening = Fixtures.uniformAccounts(3, 100);
        LedgerSeed.reset(dataSource(), opening);
        PostgresLedger serverOne = new PostgresLedger(dataSource(), LockMode.PESSIMISTIC);
        PostgresLedger serverTwo = new PostgresLedger(dataSource(), LockMode.PESSIMISTIC);
        int ids = 60;
        AccountId[] accounts = {A, B, C};
        Map<String, Set<ApplyResult>> answers = new ConcurrentHashMap<>();

        // Amounts are large enough that later transfers hit REJECTED_FUNDS, so both outcomes are replayed.
        Concurrently.run(8, Duration.ofMinutes(3), thread -> {
            PostgresLedger ledger = thread % 2 == 0 ? serverOne : serverTwo;
            for (int step = 0; step < ids; step++) {
                int i = (step + thread * 7) % ids; // overlapping sets, a different order per thread
                ApplyResult result = ledger.applyWithResult(
                        transfer("id-" + i, accounts[i % 3], accounts[(i + 1) % 3], 10 + i % 40));
                answers.computeIfAbsent("id-" + i, k -> ConcurrentHashMap.newKeySet())
                        .add(result);
            }
        });

        assertThat(answers).hasSize(ids);
        answers.forEach((id, seen) ->
                assertThat(seen).as("one answer for %s from both instances", id).hasSize(1));
        List<TransferId> accepted = answers.entrySet().stream()
                .filter(e -> e.getValue().iterator().next().outcome() == Outcome.POSTED)
                .map(e -> TransferId.of(e.getKey()))
                .toList();
        assertThat(accepted).as("some posted").isNotEmpty();
        assertThat(accepted.size()).as("some rejected for funds").isLessThan(ids);
        InvariantReport report =
                InvariantChecker.checkLedger(opening, serverOne.snapshot(), serverTwo.postings(), accepted);
        assertThat(report.allPassed()).as(report.toString()).isTrue();
        assertThat(count("posting")).isEqualTo(accepted.size());
        assertThat(count("transfer")).isEqualTo(ids);
    }

    @Test
    void REQ_DB_005_after_a_restart_the_same_id_returns_the_original_outcome_and_posts_nothing() throws Exception {
        PostgresLedger before = ledgerWith(Map.of(A, Money.of(100), B, Money.of(50)));
        ApplyResult original = before.applyWithResult(transfer("survivor", A, B, 25));
        before = null; // the old server is gone; only the database remembers

        HikariDataSource freshPool = newPoolOnSameDatabase();
        try {
            PostgresLedger after = new PostgresLedger(freshPool, LockMode.PESSIMISTIC);

            ApplyResult replay = after.applyWithResult(transfer("survivor", A, B, 25));

            assertThat(replay).isEqualTo(original);
            assertThat(after.balance(A)).contains(Money.of(75));
            assertThat(after.balance(B)).contains(Money.of(75));
            assertThat(after.postings()).hasSize(1);
        } finally {
            freshPool.close();
        }
    }

    @Test
    void REQ_DB_005_a_duplicate_of_a_rejected_for_funds_transfer_returns_the_stored_rejection_even_if_it_would_now_fit()
            throws SQLException {
        PostgresLedger ledger = ledgerWith(Map.of(A, Money.of(10), B, Money.of(0)));
        assertThat(ledger.apply(transfer("x", A, B, 50))).isEqualTo(Outcome.REJECTED_FUNDS);
        fund(A, 1_000); // a different transfer id (or a deposit) makes the original affordable

        ApplyResult replay = ledger.applyWithResult(transfer("x", A, B, 50));

        assertThat(replay.outcome()).as("same id, same answer").isEqualTo(Outcome.REJECTED_FUNDS);
        assertThat(replay.posting()).isEmpty();
        assertThat(ledger.balance(A)).contains(Money.of(1_010));
        assertThat(count("posting")).isZero();
        assertThat(rows("SELECT outcome FROM transfer WHERE id = 'x'")).containsExactly("REJECTED_FUNDS");
        assertThat(ledger.apply(transfer("x-again", A, B, 50)))
                .as("a new attempt needs a new id")
                .isEqualTo(Outcome.POSTED);
    }

    @Test
    void REQ_DB_005_a_transfer_to_an_unknown_account_is_stored_as_rejected_invalid_and_replays_the_same_answer()
            throws SQLException {
        PostgresLedger ledger = ledgerWith(Map.of(A, Money.of(10), B, Money.of(5)));

        assertThat(ledger.apply(transfer("ghost", A, AccountId.of(99), 1))).isEqualTo(Outcome.REJECTED_INVALID);

        assertThat(rows("SELECT id || ',' || outcome || ',' || coalesce(posting_seq::text, 'null') FROM transfer"))
                .as("the rejection is recorded, like REJECTED_FUNDS")
                .containsExactly("ghost,REJECTED_INVALID,null");
        assertThat(ledger.apply(transfer("ghost", A, AccountId.of(99), 1))).isEqualTo(Outcome.REJECTED_INVALID);
        assertThat(count("transfer")).isEqualTo(1);
        assertThat(count("posting")).isZero();
        assertThat(ledger.snapshot()).isEqualTo(Map.of(A, Money.of(10), B, Money.of(5)));
    }

    /** Characterisation of today: the validator rejects these before the database, so no row is stored. */
    @Test
    void REQ_DB_005_same_account_and_non_positive_amount_are_rejected_before_the_database_and_store_no_row()
            throws SQLException {
        PostgresLedger ledger = ledgerWith(Map.of(A, Money.of(10), B, Money.of(5)));

        assertThat(ledger.apply(transfer("same", A, A, 1))).isEqualTo(Outcome.REJECTED_INVALID);
        assertThat(ledger.apply(transfer("zero", A, B, 0))).isEqualTo(Outcome.REJECTED_INVALID);

        assertThat(count("transfer")).isZero();
    }

    @Test
    void REQ_DB_005_a_timed_out_attempt_leaves_the_id_free_so_a_retry_is_processed_fresh() throws Exception {
        LedgerSeed.reset(dataSource(), Map.of(A, Money.of(100), B, Money.of(100)));
        PostgresLedger impatient =
                new PostgresLedger(dataSource(), LockMode.PESSIMISTIC, Duration.ofMillis(300), Duration.ofSeconds(30));

        try (Connection holder = dataSource().getConnection()) {
            holder.setAutoCommit(false);
            try (Statement s = holder.createStatement()) {
                s.execute("SELECT id FROM account WHERE id = 1 FOR UPDATE");
            }
            assertThatThrownBy(() -> impatient.apply(transfer("retry", A, B, 10)))
                    .isInstanceOf(RuntimeException.class);
            holder.rollback();
        }

        assertThat(count("transfer")).as("the timed-out attempt left no claim").isZero();
        ApplyResult retried = impatient.applyWithResult(transfer("retry", A, B, 10));
        assertThat(retried.outcome()).isEqualTo(Outcome.POSTED);
        assertThat(count("posting")).isEqualTo(1);
        assertThat(impatient.balance(A)).contains(Money.of(90));
    }

    // TODO-ME: write "the same id with a different amount returns the ORIGINAL outcome": apply
    // transfer("p", A, B, 30) (POSTED), then transfer("p", A, B, 999), and assert the second result
    // equals the first and the balances moved by 30 only. In a comment explain why the ledger does not
    // compare payloads (the id is the whole idempotency key; comparing adds a failure mode and a second
    // source of truth; a caller that wants a mismatch error, like the api, can check it itself).
    // Check it: it passes on today's code; make the replay path post anew and it must fail.
    @Test
    @Disabled("TODO-ME: left for the user to write")
    void REQ_DB_005_the_same_id_with_a_different_amount_returns_the_original_outcome() {}

    // ---- helpers --------------------------------------------------------------------------------

    private static HikariDataSource newPoolOnSameDatabase() {
        HikariDataSource existing = (HikariDataSource) dataSource();
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(existing.getJdbcUrl());
        config.setUsername(existing.getUsername());
        config.setPassword(existing.getPassword());
        config.setMaximumPoolSize(4);
        return new HikariDataSource(config);
    }

    private void fund(AccountId account, long extra) throws SQLException {
        try (Connection c = dataSource().getConnection();
                Statement s = c.createStatement()) {
            s.executeUpdate("UPDATE account SET balance = balance + " + extra + " WHERE id = " + account.value());
        }
    }

    private long count(String table) throws SQLException {
        return Long.parseLong(rows("SELECT count(*) FROM " + table).get(0));
    }

    private List<String> rows(String query) throws SQLException {
        List<String> out = new ArrayList<>();
        try (Connection c = dataSource().getConnection();
                Statement s = c.createStatement();
                ResultSet rs = s.executeQuery(query)) {
            while (rs.next()) {
                out.add(rs.getString(1));
            }
        }
        return out;
    }
}
