package com.siddheshfulawade.settlementlab.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.siddheshfulawade.settlementlab.domain.AccountId;
import com.siddheshfulawade.settlementlab.domain.Money;
import com.siddheshfulawade.settlementlab.domain.Outcome;
import com.siddheshfulawade.settlementlab.domain.Transfer;
import com.siddheshfulawade.settlementlab.domain.TransferId;
import com.siddheshfulawade.settlementlab.pipeline.ThreadMode;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SettlementRuntimeTest {

    private static Transfer transfer(String id, long from, long to, long amount) {
        return new Transfer(TransferId.of(id), AccountId.of(from), AccountId.of(to), Money.of(amount));
    }

    @Test
    @DisplayName("V2-01 the runtime wires ledger, engine, outbox and general ledger from the properties")
    void wiresTheStack() throws Exception {
        var properties =
                SettlementProperties.defaults().withLedger(LedgerChoice.CAS).withThreads(ThreadMode.PLATFORM);

        try (var runtime = SettlementRuntime.start(properties)) {
            Outcome outcome = runtime.engine()
                    .submit(transfer("t-1", 1, 2, 100))
                    .toCompletableFuture()
                    .get(10, TimeUnit.SECONDS);

            assertThat(outcome).isEqualTo(Outcome.POSTED);
            assertThat(runtime.ledger().getClass().getSimpleName()).isEqualTo("CasLedger");
            assertThat(runtime.engine().snapshot()).hasSize(properties.accounts());
        }
    }

    @Test
    @DisplayName("REQ-API-006 closing refuses new transfers and leaves the general ledger caught up")
    void closeDrainsEverything() throws Exception {
        var runtime = SettlementRuntime.start(SettlementProperties.defaults());
        for (int i = 0; i < 200; i++) {
            runtime.engine().submit(transfer("t-" + i, i % 10 + 1, (i + 1) % 10 + 1, 1));
        }

        runtime.close();

        assertThat(runtime.engine().isClosed()).isTrue();
        assertThat(runtime.generalLedger().entries())
                .containsExactlyElementsOf(runtime.engine().postings());
        assertThatThrownBy(() -> runtime.engine().submit(transfer("late", 1, 2, 1)))
                .isInstanceOf(IllegalStateException.class);
        runtime.close(); // idempotent
    }
}
