package com.siddheshfulawade.settlementlab.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.siddheshfulawade.settlementlab.pipeline.ThreadMode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = {"settlement.ledger=CAS", "settlement.threads=PLATFORM", "settlement.accounts=50"})
class SettlementApiApplicationTest {

    @Autowired
    SettlementRuntime runtime;

    @Test
    @DisplayName("V2-01 the application starts with the engine configured from settlement.* properties")
    void startsWithConfiguredEngine() {
        assertThat(runtime.properties().ledger()).isEqualTo(LedgerChoice.CAS);
        assertThat(runtime.properties().threads()).isEqualTo(ThreadMode.PLATFORM);
        assertThat(runtime.engine().snapshot()).hasSize(50);
        assertThat(runtime.engine().isClosed()).isFalse();
    }
}
