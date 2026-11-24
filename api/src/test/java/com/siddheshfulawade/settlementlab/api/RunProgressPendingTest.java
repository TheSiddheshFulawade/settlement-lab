package com.siddheshfulawade.settlementlab.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.siddheshfulawade.settlementlab.domain.AccountId;
import com.siddheshfulawade.settlementlab.domain.Money;
import com.siddheshfulawade.settlementlab.domain.Transfer;
import com.siddheshfulawade.settlementlab.domain.TransferId;
import com.siddheshfulawade.settlementlab.gl.FaultProfile;
import com.siddheshfulawade.settlementlab.pipeline.SettlementListener;
import java.time.Clock;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class RunProgressPendingTest {

    private static final int TRANSFERS = 20; // more than the 16 sender permits

    @Test
    @DisplayName(
            "REQ-RUN-003 metrics outboxSize counts postings waiting for a sender or being sent, and is 0 after the drain")
    void outboxSizeCountsPostingsStillOnTheirWayToTheGeneralLedger() {
        FaultProfile slowGl = FaultProfile.NONE.withLatency(Duration.ofSeconds(1));
        var runtime = SettlementRuntime.start(SettlementProperties.defaults(), SettlementListener.NONE, slowGl);
        try {
            var progress = new RunProgress(
                    runtime, Clock.systemUTC(), Clock.systemUTC().instant(), TRANSFERS);
            for (int i = 0; i < TRANSFERS; i++) {
                runtime.engine()
                        .submit(new Transfer(
                                TransferId.of("p-" + i),
                                AccountId.of(i % 10 + 1),
                                AccountId.of((i + 1) % 10 + 1),
                                Money.of(1)));
            }

            await().atMost(Duration.ofSeconds(5))
                    .pollInterval(Duration.ofMillis(5))
                    .until(() -> progress.metrics().outboxSize() == TRANSFERS);
            assertThat(runtime.outbox().outboxSize())
                    .as("nothing failed, so the engine's own outbox is empty")
                    .isZero();

            runtime.close(); // drains: every posting reaches the general ledger

            assertThat(progress.metrics().outboxSize()).isZero();
        } finally {
            runtime.close();
        }
    }
}
