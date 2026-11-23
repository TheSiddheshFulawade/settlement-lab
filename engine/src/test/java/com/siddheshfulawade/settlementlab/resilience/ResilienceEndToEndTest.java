package com.siddheshfulawade.settlementlab.resilience;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.siddheshfulawade.settlementlab.domain.AccountId;
import com.siddheshfulawade.settlementlab.domain.InvariantChecker;
import com.siddheshfulawade.settlementlab.domain.InvariantReport;
import com.siddheshfulawade.settlementlab.domain.LockingLedger;
import com.siddheshfulawade.settlementlab.domain.Money;
import com.siddheshfulawade.settlementlab.domain.Outcome;
import com.siddheshfulawade.settlementlab.domain.Transfer;
import com.siddheshfulawade.settlementlab.domain.TransferId;
import com.siddheshfulawade.settlementlab.gl.FakeGeneralLedger;
import com.siddheshfulawade.settlementlab.gl.FaultProfile;
import com.siddheshfulawade.settlementlab.gl.OutboxPublisher;
import com.siddheshfulawade.settlementlab.pipeline.EngineConfig;
import com.siddheshfulawade.settlementlab.pipeline.SettlementEngine;
import com.siddheshfulawade.settlementlab.pipeline.SettlementListener;
import com.siddheshfulawade.settlementlab.pipeline.ThreadMode;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class ResilienceEndToEndTest {

    private static final Duration WAIT = Duration.ofSeconds(30);

    @ParameterizedTest
    @EnumSource(ThreadMode.class)
    @DisplayName("REQ-LAB-011 REQ-LAB-012 an outage opens the breaker, the outbox holds postings, and after"
            + " recovery the breaker closes and the general ledger matches the ledger")
    void outageOpensThenClosesTheBreaker(ThreadMode mode) throws Exception {
        Map<AccountId, Money> opening = new LinkedHashMap<>();
        for (long id = 1; id <= 4; id++) {
            opening.put(AccountId.of(id), Money.of(100_000));
        }
        var ledger = new LockingLedger(opening);
        // The outage window is driven by a test clock, not wall time: a slow runner cannot skip past it.
        AtomicLong nanoClock = new AtomicLong(0);
        var gl = new FakeGeneralLedger(FaultProfile.outage(Duration.ZERO, Duration.ofMillis(400)), 11, nanoClock::get);
        var config = new ResilienceConfig(3, Duration.ofMillis(2), 2.0, 0.5, 10, 5, 50, Duration.ofMillis(150), 2);
        var resilient = new ResilientPublisher(gl, config);
        List<TransferId> posted = new ArrayList<>();

        try (var outbox = new OutboxPublisher(resilient, Duration.ofMillis(10));
                var engine = new SettlementEngine(
                        ledger, EngineConfig.of(mode, 4, 1_000), SettlementListener.NONE, outbox)) {
            for (int i = 0; i < 200; i++) {
                if (i == 20) {
                    // Still inside the outage: wait until it has opened the breaker and filled the outbox.
                    await().atMost(WAIT).until(() -> resilient.breakerOpens() > 0 && outbox.outboxPeak() > 0);
                    nanoClock.set(Duration.ofSeconds(10).toNanos()); // past the window end: the GL recovers
                }
                var t = new Transfer(
                        TransferId.of("t-" + i), AccountId.of(i % 4 + 1), AccountId.of((i + 1) % 4 + 1), Money.of(10));
                if (engine.submit(t).toCompletableFuture().get(WAIT.toSeconds(), TimeUnit.SECONDS) == Outcome.POSTED) {
                    posted.add(t.id());
                }
            }

            assertThat(engine.drain(WAIT)).isTrue();
            assertThat(outbox.drain(WAIT)).isTrue();

            assertThat(resilient.breakerOpens())
                    .as("the outage opened the breaker")
                    .isPositive();
            assertThat(resilient.breakerState()).as("recovery closed it again").isEqualTo("CLOSED");
            assertThat(outbox.outboxPeak()).isPositive();
            InvariantReport report =
                    InvariantChecker.checkRun(opening, engine.snapshot(), engine.postings(), posted, gl.entries());
            assertThat(report.allPassed()).as(report.toString()).isTrue();
        }
    }
}
