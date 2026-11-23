package com.siddheshfulawade.settlementlab.postgres;

import static com.siddheshfulawade.settlementlab.postgres.OutboxTestSupport.count;
import static com.siddheshfulawade.settlementlab.postgres.OutboxTestSupport.rows;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.siddheshfulawade.settlementlab.gl.FakeGeneralLedger;
import com.siddheshfulawade.settlementlab.gl.FaultProfile;
import com.siddheshfulawade.settlementlab.gl.GeneralLedgerException;
import com.siddheshfulawade.settlementlab.gl.GeneralLedgerPort;
import com.siddheshfulawade.settlementlab.testing.Concurrently;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * REQ-DB-007, the outbox relay (SPEC.md 16.4).
 *
 * <p>Assumed API, in this package: {@code new OutboxRelay(DataSource, GeneralLedgerPort, int batchSize,
 * Duration lease)}; {@code int relayOnce()} claims due rows (ORDER BY posting_seq, LIMIT batchSize, FOR
 * UPDATE SKIP LOCKED), sets {@code next_attempt_at = now() + lease}, commits, publishes each row in
 * claim order with no transaction open, deletes the accepted rows, and for a row whose publish threw any
 * RuntimeException sets {@code attempts = attempts + 1, next_attempt_at = now() + lease}; it returns the
 * number of rows the GL accepted. {@code boolean drain(Duration)} calls relayOnce until the outbox is
 * empty (waiting for rows that are not yet due) and returns false if the timeout passes first. The clock
 * is the database's now().
 */
class OutboxRelayTest extends PostgresTestBase {

    private static final Duration LONG_LEASE = Duration.ofMinutes(10);
    private static final Duration WAIT = Duration.ofSeconds(60);

    private static FakeGeneralLedger healthyGl() {
        return new FakeGeneralLedger(FaultProfile.NONE, 1);
    }

    @ParameterizedTest
    @EnumSource(LockMode.class)
    void REQ_DB_007_relay_publishes_every_posting_and_empties_the_outbox(LockMode mode) {
        PostgresLedger ledger = OutboxTestSupport.seedAndPost(dataSource(), mode, 25);
        FakeGeneralLedger gl = healthyGl();
        OutboxRelay relay = new OutboxRelay(dataSource(), gl, 10, LONG_LEASE);

        assertThat(relay.drain(WAIT)).isTrue();

        assertThat(count(dataSource(), "outbox")).isZero();
        assertThat(gl.entries()).containsExactlyElementsOf(ledger.postings());
        assertThat(gl.duplicatePublishes()).isZero();
        assertThat(count(dataSource(), "posting")).as("postings are kept").isEqualTo(25);
    }

    @Test
    void REQ_DB_007_one_relay_pass_publishes_at_most_batch_size_rows_lowest_sequence_first() {
        OutboxTestSupport.seedAndPost(dataSource(), LockMode.PESSIMISTIC, 10);
        List<Long> order = new CopyOnWriteArrayList<>();
        GeneralLedgerPort recording = posting -> {
            order.add(posting.sequence());
            return GeneralLedgerPort.Ack.RECORDED;
        };
        OutboxRelay relay = new OutboxRelay(dataSource(), recording, 3, LONG_LEASE);

        int published = relay.relayOnce();

        assertThat(published).isEqualTo(3);
        assertThat(order).containsExactly(1L, 2L, 3L);
        assertThat(rows(dataSource(), "SELECT posting_seq FROM outbox ORDER BY posting_seq"))
                .hasSize(7)
                .first()
                .isEqualTo("4");
    }

    @Test
    void REQ_DB_007_a_failed_publish_stays_with_attempts_plus_one_and_a_later_next_attempt() {
        OutboxTestSupport.seedAndPost(dataSource(), LockMode.PESSIMISTIC, 6);
        AtomicLong clock = new AtomicLong();
        FakeGeneralLedger outage =
                new FakeGeneralLedger(FaultProfile.outage(Duration.ZERO, Duration.ofHours(1)), 1, clock::get);
        OutboxRelay relay = new OutboxRelay(dataSource(), outage, 10, Duration.ofSeconds(30));

        int published = relay.relayOnce();

        assertThat(published).isZero();
        assertThat(count(dataSource(), "outbox")).isEqualTo(6);
        assertThat(count(dataSource(), "outbox WHERE attempts = 1")).isEqualTo(6);
        assertThat(count(dataSource(), "outbox WHERE next_attempt_at > now() + interval '10 seconds'"))
                .as("pushed back into the future")
                .isEqualTo(6);
        long callsAfterFirstPass = outage.calls();
        assertThat(relay.relayOnce()).as("nothing is due yet").isZero();
        assertThat(outage.calls()).as("rows not due are not even tried").isEqualTo(callsAfterFirstPass);
    }

    @Test
    void REQ_DB_007_only_the_failing_posting_stays_while_the_others_are_deleted() {
        OutboxTestSupport.seedAndPost(dataSource(), LockMode.PESSIMISTIC, 5);
        FakeGeneralLedger gl = healthyGl();
        GeneralLedgerPort failsOnThird = posting -> {
            if (posting.sequence() == 3) {
                throw new GeneralLedgerException(GeneralLedgerException.Kind.ERROR, "refused");
            }
            return gl.publish(posting);
        };
        OutboxRelay relay = new OutboxRelay(dataSource(), failsOnThird, 10, Duration.ofSeconds(30));

        assertThat(relay.relayOnce()).isEqualTo(4);

        assertThat(rows(dataSource(), "SELECT posting_seq || ':' || attempts FROM outbox"))
                .containsExactly("3:1");
    }

    @Test
    void REQ_DB_007_a_row_with_a_live_lease_is_not_claimed_by_a_second_relay() throws Exception {
        OutboxTestSupport.seedAndPost(dataSource(), LockMode.PESSIMISTIC, 4);
        CountDownLatch insideFirstCall = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        FakeGeneralLedger gl = healthyGl();
        GeneralLedgerPort blocking = posting -> {
            insideFirstCall.countDown();
            try {
                if (!release.await(60, java.util.concurrent.TimeUnit.SECONDS)) {
                    throw new IllegalStateException("test never released the first relay");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return gl.publish(posting);
        };
        AtomicInteger secondCalls = new AtomicInteger();
        GeneralLedgerPort counting = posting -> {
            secondCalls.incrementAndGet();
            return gl.publish(posting);
        };
        OutboxRelay first = new OutboxRelay(dataSource(), blocking, 10, LONG_LEASE);
        OutboxRelay second = new OutboxRelay(dataSource(), counting, 10, LONG_LEASE);
        AtomicInteger firstPublished = new AtomicInteger(-1);
        Thread firstThread = Thread.ofPlatform().start(() -> firstPublished.set(first.relayOnce()));

        try {
            assertThat(insideFirstCall.await(30, java.util.concurrent.TimeUnit.SECONDS))
                    .isTrue();
            // First relay holds all four rows on a live lease and is stuck inside the GL call.
            assertThat(second.relayOnce()).isZero();
            assertThat(secondCalls).hasValue(0);
        } finally {
            release.countDown();
            firstThread.join(Duration.ofSeconds(30).toMillis());
        }

        assertThat(firstPublished).hasValue(4);
        assertThat(count(dataSource(), "outbox")).isZero();
        assertThat(gl.duplicatePublishes()).isZero();
    }

    @Test
    void REQ_DB_007_two_relays_running_together_publish_each_posting_once_and_together_drain_everything()
            throws Exception {
        int postings = 300;
        PostgresLedger ledger = OutboxTestSupport.seedAndPost(dataSource(), LockMode.PESSIMISTIC, postings);
        FakeGeneralLedger gl = new FakeGeneralLedger(FaultProfile.NONE.withLatency(Duration.ofMillis(1)), 5);
        Map<String, AtomicInteger> callsPerTransfer = new ConcurrentHashMap<>();
        GeneralLedgerPort counting = posting -> {
            callsPerTransfer
                    .computeIfAbsent(posting.transferId().value(), k -> new AtomicInteger())
                    .incrementAndGet();
            return gl.publish(posting);
        };
        // Leases outlive the test, so no live lease can expire and excuse a second publish.
        OutboxRelay one = new OutboxRelay(dataSource(), counting, 7, LONG_LEASE);
        OutboxRelay two = new OutboxRelay(dataSource(), counting, 11, LONG_LEASE);

        Concurrently.run(2, Duration.ofMinutes(2), thread -> {
            OutboxRelay relay = thread == 0 ? one : two;
            while (count(dataSource(), "outbox WHERE next_attempt_at <= now()") > 0) {
                relay.relayOnce();
            }
        });

        await().atMost(WAIT).until(() -> count(dataSource(), "outbox") == 0);
        assertThat(callsPerTransfer).hasSize(postings);
        assertThat(callsPerTransfer.values()).allSatisfy(n -> assertThat(n).hasValue(1));
        assertThat(gl.duplicatePublishes()).isZero();
        assertThat(gl.entries()).containsExactlyElementsOf(ledger.postings());
    }

    @Test
    void REQ_DB_007_no_transaction_is_open_and_no_claimed_row_is_locked_during_the_gl_call() {
        OutboxTestSupport.seedAndPost(dataSource(), LockMode.PESSIMISTIC, 5);
        List<String> violations = new CopyOnWriteArrayList<>();
        FakeGeneralLedger gl = healthyGl();
        GeneralLedgerPort inspecting = posting -> {
            try (Connection other = dataSource().getConnection();
                    Statement s = other.createStatement()) {
                other.setAutoCommit(false);
                try {
                    s.execute("SELECT 1 FROM outbox WHERE posting_seq = " + posting.sequence() + " FOR UPDATE NOWAIT");
                } catch (SQLException locked) {
                    violations.add("row " + posting.sequence() + " is locked: " + locked.getMessage());
                }
                other.rollback();
                var idle = s.executeQuery("SELECT count(*) FROM pg_stat_activity WHERE datname = current_database()"
                        + " AND pid <> pg_backend_pid() AND state LIKE 'idle in transaction%'");
                idle.next();
                if (idle.getLong(1) > 0) {
                    violations.add("idle-in-transaction sessions: " + idle.getLong(1));
                }
            } catch (SQLException e) {
                violations.add("inspection failed: " + e.getMessage());
            }
            return gl.publish(posting);
        };
        OutboxRelay relay = new OutboxRelay(dataSource(), inspecting, 5, LONG_LEASE);

        assertThat(relay.relayOnce()).isEqualTo(5);

        assertThat(violations).as("GL call must run outside any transaction").isEmpty();
    }

    // TODO-ME: write "a crashed relay's lease expires and another relay picks the row up". Seed two
    // postings, then simulate the crash by claiming one row by hand: UPDATE outbox SET next_attempt_at
    // = now() + interval '1 second' WHERE posting_seq = 1 (the lease a dead relay left behind). Assert
    // that relayOnce() on a live relay publishes only seq 2 at once, then (Awaitility, up to 30 s)
    // that a later relayOnce() publishes seq 1 and the outbox is empty. Check it: it passes on the real
    // relay; change the claim query to ignore next_attempt_at and the first assertion must fail.
    @Test
    @Disabled("TODO-ME: left for the user to write")
    void REQ_DB_007_a_crashed_relays_lease_expires_and_another_relay_picks_the_row_up() {}
}
