package com.siddheshfulawade.settlementlab.gl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class FaultProfileTest {

    @Test
    @DisplayName("REQ-LAB-013 the named profiles have the documented fault rates")
    void namedProfiles() {
        assertThat(FaultProfile.NONE.errorPercent()).isZero();
        assertThat(FaultProfile.FLAKY.errorPercent()).isEqualTo(10);
        assertThat(FaultProfile.FLAKY.lostAckPercent()).isEqualTo(5);
        assertThat(FaultProfile.FLAKY.latency()).isPositive();
        assertThat(FaultProfile.DUPLICATE_ACKS.lostAckPercent()).isEqualTo(30);
        FaultProfile outage = FaultProfile.outage(Duration.ofMillis(10), Duration.ofMillis(20));
        assertThat(outage.outageStart()).isEqualTo(Duration.ofMillis(10));
        assertThat(outage.outageLength()).isEqualTo(Duration.ofMillis(20));
        assertThat(outage.name()).isEqualTo("OUTAGE");
    }

    @Test
    @DisplayName("REQ-LAB-013 impossible rates and negative durations are refused")
    void refusesNonsense() {
        Duration zero = Duration.ZERO;
        assertThatThrownBy(() -> new FaultProfile("x", -1, 0, zero, zero, zero))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new FaultProfile("x", 60, 41, zero, zero, zero))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new FaultProfile("x", 0, 0, Duration.ofMillis(-1), zero, zero))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new FaultProfile("x", 0, 0, zero, zero, Duration.ofMillis(-1)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
