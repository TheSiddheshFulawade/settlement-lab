package com.siddheshfulawade.settlementlab.resilience;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ResilienceConfigTest {

    @Test
    @DisplayName("REQ-LAB-011 the defaults are the starting values from SPEC.md section 4.4")
    void defaultsMatchTheSpec() {
        ResilienceConfig config = ResilienceConfig.defaults();

        assertThat(config.maxAttempts()).isEqualTo(3);
        assertThat(config.slidingWindowSize()).isEqualTo(20);
        assertThat(config.failureRatePercent()).isEqualTo(50);
        assertThat(config.openWait()).isEqualTo(Duration.ofSeconds(2));
        assertThat(config.randomizationFactor()).isPositive();
    }

    @Test
    @DisplayName("nonsense settings are refused")
    void refusesNonsense() {
        Duration ms = Duration.ofMillis(1);
        assertThatThrownBy(() -> new ResilienceConfig(0, ms, 2, 0.5, 20, 10, 50, ms, 3))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ResilienceConfig(3, ms, 0.5, 0.5, 20, 10, 50, ms, 3))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ResilienceConfig(3, ms, 2, 1.5, 20, 10, 50, ms, 3))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ResilienceConfig(3, ms, 2, 0.5, 0, 10, 50, ms, 3))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ResilienceConfig(3, ms, 2, 0.5, 20, 10, 101, ms, 3))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ResilienceConfig(3, Duration.ZERO, 2, 0.5, 20, 10, 50, ms, 3))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
