package com.siddheshfulawade.settlementlab.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.siddheshfulawade.settlementlab.gl.FaultProfile;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SettlementPropertiesTest {

    @Test
    @DisplayName("V2-01 defaults: locking ledger, virtual threads, no faults, 1,000 accounts")
    void defaults() {
        SettlementProperties defaults = SettlementProperties.defaults();

        assertThat(defaults.ledger()).isEqualTo(LedgerChoice.LOCKING);
        assertThat(defaults.accounts()).isEqualTo(1_000);
        assertThat(defaults.faultProfile()).isEqualTo(FaultProfile.NONE);
    }

    @Test
    @DisplayName("each named fault maps to its v1 fault profile")
    void faultNames() {
        assertThat(SettlementProperties.defaults().withFaults(FaultChoice.FLAKY).faultProfile())
                .isEqualTo(FaultProfile.FLAKY);
        assertThat(SettlementProperties.defaults()
                        .withFaults(FaultChoice.DUPLICATE_ACKS)
                        .faultProfile())
                .isEqualTo(FaultProfile.DUPLICATE_ACKS);
        assertThat(SettlementProperties.defaults()
                        .withFaults(FaultChoice.OUTAGE)
                        .faultProfile()
                        .name())
                .isEqualTo("OUTAGE");
    }

    @Test
    @DisplayName("nonsense settings are refused at start-up, not at the first request")
    void refusesNonsense() {
        SettlementProperties d = SettlementProperties.defaults();
        assertThatThrownBy(() -> new SettlementProperties(
                        d.ledger(),
                        d.threads(),
                        1,
                        d.workers(),
                        d.queueCapacity(),
                        d.openingBalance(),
                        d.offerTimeout(),
                        d.drainTimeout(),
                        d.faults(),
                        d.seed()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SettlementProperties(
                        d.ledger(),
                        d.threads(),
                        d.accounts(),
                        d.workers(),
                        d.queueCapacity(),
                        -1,
                        d.offerTimeout(),
                        d.drainTimeout(),
                        d.faults(),
                        d.seed()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SettlementProperties(
                        d.ledger(),
                        d.threads(),
                        d.accounts(),
                        d.workers(),
                        d.queueCapacity(),
                        d.openingBalance(),
                        d.offerTimeout(),
                        Duration.ofMillis(-1),
                        d.faults(),
                        d.seed()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // TODO-ME: write `boundDefaultsMatchDefaults`: the @DefaultValue strings and defaults() are two
    //  copies of the same numbers and can drift. Use Spring's Binder on an empty
    //  MapConfigurationPropertySource to bind "settlement" to SettlementProperties, and assert the
    //  result equals SettlementProperties.defaults(). Check it: the test passes; then change
    //  @DefaultValue("8") on workers to "9" and see it fail.
}
