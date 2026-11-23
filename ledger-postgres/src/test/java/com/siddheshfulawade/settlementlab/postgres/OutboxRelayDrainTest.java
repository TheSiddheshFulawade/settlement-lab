package com.siddheshfulawade.settlementlab.postgres;

import static com.siddheshfulawade.settlementlab.domain.Fixtures.transfer;
import static com.siddheshfulawade.settlementlab.postgres.OutboxTestSupport.ACCOUNTS;
import static com.siddheshfulawade.settlementlab.postgres.OutboxTestSupport.count;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.siddheshfulawade.settlementlab.domain.InvariantChecker;
import com.siddheshfulawade.settlementlab.domain.InvariantResult;
import com.siddheshfulawade.settlementlab.domain.Outcome;
import com.siddheshfulawade.settlementlab.domain.PostingPublisher;
import com.siddheshfulawade.settlementlab.gl.FakeGeneralLedger;
import com.siddheshfulawade.settlementlab.gl.FaultProfile;
import com.siddheshfulawade.settlementlab.pipeline.EngineConfig;
import com.siddheshfulawade.settlementlab.pipeline.SettlementEngine;
import com.siddheshfulawade.settlementlab.pipeline.SettlementListener;
import com.siddheshfulawade.settlementlab.pipeline.ThreadMode;
import com.siddheshfulawade.settlementlab.resilience.ResilienceConfig;
import com.siddheshfulawade.settlementlab.resilience.ResilientPublisher;
import com.zaxxer.hikari.HikariDataSource;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * REQ-DB-008 and REQ-LAB-012 with the database outbox: after a restart, and under GL faults, every
 * posting reaches the general ledger exactly once. Uses the {@link OutboxRelay} API assumed in
 * {@link OutboxRelayTest}.
 */
class OutboxRelayDrainTest extends PostgresTestBase {

    private static final Duration WAIT = Duration.ofSeconds(90);

    @ParameterizedTest
    @EnumSource(LockMode.class)
    void REQ_DB_008_after_a_stop_with_rows_in_the_outbox_a_new_relay_on_a_new_pool_delivers_each_posting_once(
            LockMode mode) throws Exception {
        LedgerSeedHolder.reset(dataSource());
        AtomicLong clock = new AtomicLong();
        FakeGeneralLedger gl =
                new FakeGeneralLedger(FaultProfile.outage(Duration.ZERO, Duration.ofHours(1)), 3, clock::get);

        // First life: the engine posts with a no-op publisher; the only GL attempt meets the outage.
        HikariDataSource firstPool = OutboxTestSupport.newPoolOnSameDatabase(dataSource(), 8);
        List<Outcome> outcomes = new ArrayList<>();
        try (SettlementEngine engine = new SettlementEngine(
                new PostgresLedger(firstPool, mode),
                EngineConfig.of(ThreadMode.PLATFORM, 4, 500),
                SettlementListener.NONE,
                PostingPublisher.NONE)) {
            List<CompletionStage<Outcome>> pending = new ArrayList<>();
            for (int i = 0; i < 120; i++) {
                pending.add(engine.submit(transfer("e-" + i, ACCOUNTS[i % 4], ACCOUNTS[(i + 1) % 4], 1 + i % 5)));
            }
            assertThat(engine.drain(WAIT)).isTrue();
            pending.forEach(p -> outcomes.add(p.toCompletableFuture().join()));
            assertThat(new OutboxRelay(firstPool, gl, 20, Duration.ofMillis(100)).relayOnce())
                    .as("GL is down")
                    .isZero();
        }
        firstPool.close(); // everything of the first life is gone
        // OPTIMISTIC may, in a rare run, give up on a hot pair after 10 attempts: that stores nothing,
        // so it is a legal outcome here. Every POSTED transfer must have left exactly one outbox row.
        assertThat(outcomes).isSubsetOf(Outcome.POSTED, Outcome.REJECTED_CONTENTION);
        long posted = outcomes.stream().filter(o -> o == Outcome.POSTED).count();
        assertThat(posted).as("almost everything posts").isGreaterThan(100);
        assertThat(count(dataSource(), "outbox")).as("rows survive the stop").isEqualTo(posted);
        assertThat(gl.entries()).isEmpty();

        // Second life: a new pool and a new relay, the GL has recovered (test clock past the window).
        clock.set(Duration.ofHours(2).toNanos());
        HikariDataSource secondPool = OutboxTestSupport.newPoolOnSameDatabase(dataSource(), 4);
        try {
            OutboxRelay relay = new OutboxRelay(secondPool, gl, 20, Duration.ofMillis(100));
            assertThat(relay.drain(WAIT)).isTrue();

            PostgresLedger reader = new PostgresLedger(secondPool, mode);
            InvariantResult result = InvariantChecker.glMatchesPostings(reader.postings(), gl.entries());
            assertThat(result.passed()).as(result.toString()).isTrue();
            assertThat(gl.entries()).hasSize((int) posted);
            assertThat(gl.duplicatePublishes())
                    .as("each posting delivered once")
                    .isZero();
            assertThat(count(secondPool, "outbox")).isZero();
        } finally {
            secondPool.close();
        }
    }

    @ParameterizedTest
    @EnumSource(value = FaultKind.class)
    void REQ_LAB_012_REQ_DB_007_under_flaky_or_duplicate_ack_faults_with_retries_the_outbox_drains_and_the_gl_matches(
            FaultKind kind) {
        PostgresLedger ledger = OutboxTestSupport.seedAndPost(dataSource(), LockMode.PESSIMISTIC, 150);
        FakeGeneralLedger gl = new FakeGeneralLedger(kind.profile, 42);
        var config = new ResilienceConfig(3, Duration.ofMillis(2), 2.0, 0.5, 20, 10, 50, Duration.ofMillis(100), 2);
        OutboxRelay relay =
                new OutboxRelay(dataSource(), new ResilientPublisher(gl, config), 10, Duration.ofMillis(50));

        assertThat(relay.drain(WAIT)).as("outbox drains despite faults").isTrue();

        await().atMost(WAIT).until(() -> count(dataSource(), "outbox") == 0);
        InvariantResult result = InvariantChecker.glMatchesPostings(ledger.postings(), gl.entries());
        assertThat(result.passed()).as(result.toString()).isTrue();
        assertThat(gl.entries()).hasSize(150);
        assertThat(gl.errors() + gl.lostAcks()).as("the faults really fired").isPositive();
    }

    enum FaultKind {
        FLAKY(FaultProfile.FLAKY),
        DUPLICATE_ACKS(FaultProfile.DUPLICATE_ACKS);

        final FaultProfile profile;

        FaultKind(FaultProfile profile) {
            this.profile = profile;
        }
    }

    /** Seeds the four accounts for the engine run. */
    private static final class LedgerSeedHolder {
        static void reset(javax.sql.DataSource dataSource) {
            LedgerSeed.reset(dataSource, OutboxTestSupport.openingBalances());
        }
    }
}
