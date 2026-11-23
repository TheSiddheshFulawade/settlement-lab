package com.siddheshfulawade.settlementlab.postgres;

import static com.siddheshfulawade.settlementlab.domain.Fixtures.A;
import static com.siddheshfulawade.settlementlab.domain.Fixtures.B;
import static com.siddheshfulawade.settlementlab.domain.Fixtures.transfer;
import static com.siddheshfulawade.settlementlab.postgres.OutboxTestSupport.count;
import static com.siddheshfulawade.settlementlab.postgres.OutboxTestSupport.outboxWithoutPosting;
import static com.siddheshfulawade.settlementlab.postgres.OutboxTestSupport.postingsWithoutOutbox;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.siddheshfulawade.settlementlab.domain.AccountId;
import com.siddheshfulawade.settlementlab.domain.Money;
import com.siddheshfulawade.settlementlab.domain.Outcome;
import com.siddheshfulawade.settlementlab.testing.Concurrently;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** REQ-DB-006: a posting and its outbox row commit together, or neither exists. */
class PostgresOutboxWriteTest extends PostgresTestBase {

    @ParameterizedTest
    @EnumSource(LockMode.class)
    void REQ_DB_006_a_posted_transfer_writes_exactly_one_outbox_row_for_its_posting(LockMode mode) {
        LedgerSeed.reset(dataSource(), Map.of(A, Money.of(100), B, Money.of(50)));
        PostgresLedger ledger = new PostgresLedger(dataSource(), mode);

        assertThat(ledger.apply(transfer("p1", A, B, 10))).isEqualTo(Outcome.POSTED);

        assertThat(count(dataSource(), "posting")).isEqualTo(1);
        assertThat(count(dataSource(), "outbox")).isEqualTo(1);
        assertThat(count(
                        dataSource(),
                        "outbox o JOIN posting p ON p.seq = o.posting_seq"
                                + " WHERE p.transfer_id = 'p1' AND o.attempts = 0 AND o.next_attempt_at <= now()"))
                .as("the row is for this posting, untried and due now")
                .isEqualTo(1);
    }

    @ParameterizedTest
    @EnumSource(LockMode.class)
    void REQ_DB_006_a_replayed_id_does_not_write_a_second_outbox_row(LockMode mode) {
        LedgerSeed.reset(dataSource(), Map.of(A, Money.of(100), B, Money.of(50)));
        PostgresLedger ledger = new PostgresLedger(dataSource(), mode);

        ledger.apply(transfer("p1", A, B, 10));
        ledger.apply(transfer("p1", A, B, 10));

        assertThat(count(dataSource(), "outbox")).isEqualTo(1);
    }

    @ParameterizedTest
    @EnumSource(LockMode.class)
    void REQ_DB_006_rejected_transfers_write_no_outbox_row(LockMode mode) {
        LedgerSeed.reset(dataSource(), Map.of(A, Money.of(10), B, Money.of(5)));
        PostgresLedger ledger = new PostgresLedger(dataSource(), mode);

        assertThat(ledger.apply(transfer("poor", A, B, 1_000))).isEqualTo(Outcome.REJECTED_FUNDS);
        assertThat(ledger.apply(transfer("ghost", A, AccountId.of(99), 1))).isEqualTo(Outcome.REJECTED_INVALID);

        assertThat(count(dataSource(), "outbox")).isZero();
        assertThat(count(dataSource(), "posting")).isZero();
    }

    @Test
    void REQ_DB_006_a_transaction_aborted_by_lock_timeout_leaves_no_transfer_posting_or_outbox_row() throws Exception {
        LedgerSeed.reset(dataSource(), Map.of(A, Money.of(100), B, Money.of(100)));
        PostgresLedger impatient =
                new PostgresLedger(dataSource(), LockMode.PESSIMISTIC, Duration.ofMillis(300), Duration.ofSeconds(30));

        try (Connection holder = dataSource().getConnection()) {
            holder.setAutoCommit(false);
            try (Statement s = holder.createStatement()) {
                s.execute("SELECT id FROM account WHERE id = 1 FOR UPDATE");
            }
            assertThatThrownBy(() -> impatient.apply(transfer("late", A, B, 10)))
                    .isInstanceOf(RuntimeException.class);
            holder.rollback();
        }

        assertThat(count(dataSource(), "transfer")).isZero();
        assertThat(count(dataSource(), "posting")).isZero();
        assertThat(count(dataSource(), "outbox")).isZero();
    }

    @ParameterizedTest
    @EnumSource(LockMode.class)
    void REQ_DB_006_under_concurrent_load_every_posting_has_an_outbox_row_and_no_outbox_row_is_orphaned(LockMode mode)
            throws Exception {
        LedgerSeed.reset(dataSource(), OutboxTestSupport.openingBalances());
        PostgresLedger ledger = new PostgresLedger(dataSource(), mode);
        AccountId[] accounts = OutboxTestSupport.ACCOUNTS;

        Concurrently.run(8, Duration.ofMinutes(3), thread -> {
            for (int i = 0; i < 40; i++) {
                // every tenth asks for far more than exists: REJECTED_FUNDS, which adds nothing to the outbox
                long amount = i % 10 == 0 ? 10_000_000 : 1 + i;
                ledger.apply(transfer(
                        "t-" + thread + "-" + i, accounts[(thread + i) % 4], accounts[(thread + i + 1) % 4], amount));
            }
        });

        long postings = count(dataSource(), "posting");
        assertThat(postings).isPositive().isLessThan(8 * 40);
        assertThat(count(dataSource(), "outbox")).isEqualTo(postings);
        assertThat(postingsWithoutOutbox(dataSource()))
                .as("posting without outbox")
                .isZero();
        assertThat(outboxWithoutPosting(dataSource()))
                .as("outbox without posting")
                .isZero();
    }
}
