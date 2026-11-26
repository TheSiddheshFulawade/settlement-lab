package com.siddheshfulawade.settlementlab.runner;

import static org.assertj.core.api.Assertions.assertThat;

import com.siddheshfulawade.settlementlab.domain.AccountId;
import com.siddheshfulawade.settlementlab.domain.Ledger;
import com.siddheshfulawade.settlementlab.domain.LockingLedger;
import com.siddheshfulawade.settlementlab.domain.Money;
import com.siddheshfulawade.settlementlab.gl.FaultProfile;
import com.siddheshfulawade.settlementlab.gl.GeneralLedgerPort;
import com.siddheshfulawade.settlementlab.pipeline.ThreadMode;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * V3-07: the runner can run a caller-supplied ledger (the database cells live in ledger-postgres,
 * because the engine must not import JDBC). Assumed API in package runner:
 * {@code LedgerFactory { String name(); Ledger create(Map); default Optional<GlDelivery> deliveryTo(GeneralLedgerPort) }},
 * {@code GlDelivery extends AutoCloseable { boolean drain(Duration); long outboxPeak(); void close(); }},
 * {@code ScenarioRunner.run(LedgerFactory, ThreadMode, FaultProfile, Workload, RunnerSettings)}.
 */
class SuppliedLedgerRunTest {

    private static final Workload WORKLOAD = new Workload(50, 1_000, 5, 50, 5, 7);

    /** A factory that delivers the way the database does: the runner publishes nothing itself. */
    private static final class DeliveringFactory implements LedgerFactory {
        final AtomicInteger drains = new AtomicInteger();
        final AtomicInteger closes = new AtomicInteger();
        final boolean drainSucceeds;
        private Ledger ledger;

        DeliveringFactory(boolean drainSucceeds) {
            this.drainSucceeds = drainSucceeds;
        }

        @Override
        public String name() {
            return "CUSTOM_DELIVERING";
        }

        @Override
        public Ledger create(Map<AccountId, Money> opening) {
            ledger = new LockingLedger(opening);
            return ledger;
        }

        @Override
        public Optional<GlDelivery> deliveryTo(GeneralLedgerPort gl) {
            return Optional.of(new GlDelivery() {
                @Override
                public boolean drain(Duration timeout) {
                    drains.incrementAndGet();
                    if (drainSucceeds) {
                        ledger.postings().forEach(gl::publish);
                    }
                    return drainSucceeds;
                }

                @Override
                public long outboxPeak() {
                    return 42;
                }

                @Override
                public void close() {
                    closes.incrementAndGet();
                }
            });
        }
    }

    @Test
    @DisplayName("REQ-DB-013 a supplied ledger runs and the row names it")
    void suppliedLedgerNamesTheRow() {
        LedgerFactory factory = new LedgerFactory() {
            @Override
            public String name() {
                return "CUSTOM_LOCKING";
            }

            @Override
            public Ledger create(Map<AccountId, Money> opening) {
                return new LockingLedger(opening);
            }
        };

        ScenarioResult row = new ScenarioRunner()
                .run(
                        factory,
                        ThreadMode.VIRTUAL,
                        FaultProfile.FLAKY.withLatency(Duration.ZERO),
                        WORKLOAD,
                        RunnerSettings.quick());

        assertThat(row.ledger()).isEqualTo("CUSTOM_LOCKING");
        assertThat(row.threads()).isEqualTo("VIRTUAL");
        assertThat(row.faults()).isEqualTo("FLAKY");
        assertThat(row.transfers()).isEqualTo(1_000);
        assertThat(row.invariantsPassed()).as(row.firstFailure()).isTrue();
    }

    @Test
    @DisplayName(
            "REQ-DB-013 a delivery is drained once, closed, and its peak is reported; the GL catches up through it")
    void deliveryIsDrainedAndClosed() {
        var factory = new DeliveringFactory(true);

        ScenarioResult row = new ScenarioRunner()
                .run(factory, ThreadMode.PLATFORM, FaultProfile.NONE, WORKLOAD, RunnerSettings.quick());

        assertThat(row.invariantsPassed()).as(row.firstFailure()).isTrue();
        assertThat(factory.drains).hasValue(1);
        assertThat(factory.closes).hasValue(1);
        assertThat(row.outboxPeak()).isEqualTo(42);
    }

    @Test
    @DisplayName("REQ-DB-013 a delivery that cannot drain fails the row and is still closed")
    void failedDrainFailsTheRow() {
        var factory = new DeliveringFactory(false);

        ScenarioResult row = new ScenarioRunner()
                .run(factory, ThreadMode.PLATFORM, FaultProfile.NONE, WORKLOAD, RunnerSettings.quick());

        assertThat(row.invariantsPassed()).isFalse();
        assertThat(row.firstFailure()).isNotBlank();
        assertThat(factory.closes).hasValue(1);
    }

    @Test
    @DisplayName(
            "REQ-LAB-015 a general ledger that has not caught up is named as the failure, not as a missing posting")
    void slowGeneralLedgerIsNamedNotReportedAsMissing() {
        var factory = new DeliveringFactory(false);

        ScenarioResult row = new ScenarioRunner()
                .run(factory, ThreadMode.PLATFORM, FaultProfile.NONE, WORKLOAD, RunnerSettings.quick());

        assertThat(row.invariantsPassed()).isFalse();
        assertThat(row.firstFailure())
                .startsWith("general ledger did not catch up before the drain timeout")
                .doesNotContain("missing from the general ledger");
    }

    @Test
    @DisplayName(
            "REQ-LAB-015 the default general-ledger drain waits 5 minutes: a FLAKY CAS cell needed 128.8 s on a laptop")
    void defaultDrainAllowsASlowMachine() {
        assertThat(RunnerSettings.defaults().drainTimeout()).isGreaterThanOrEqualTo(Duration.ofMinutes(5));
    }

    @Test
    @DisplayName("REQ-LAB-015 the in-memory path is unchanged: LedgerType rows keep their enum names")
    void inMemoryPathUnchanged() {
        var scenario =
                new Scenario(LedgerType.CAS, ThreadMode.VIRTUAL, FaultProfile.NONE, WORKLOAD, RunnerSettings.quick());

        ScenarioResult row = new ScenarioRunner().run(scenario);

        assertThat(row.ledger()).isEqualTo("CAS");
        assertThat(row.invariantsPassed()).as(row.firstFailure()).isTrue();
    }
}
