package com.siddheshfulawade.settlementlab.pipeline;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class EngineConfigTest {

    @Test
    @DisplayName("REQ-LAB-010 the defaults pick the mode and size, with short bounded waits")
    void defaults() {
        EngineConfig config = EngineConfig.of(ThreadMode.VIRTUAL, 4, 100);

        assertThat(config.threadMode()).isEqualTo(ThreadMode.VIRTUAL);
        assertThat(config.workers()).isEqualTo(4);
        assertThat(config.queueCapacity()).isEqualTo(100);
        assertThat(config.offerTimeout()).isEqualTo(Duration.ofMillis(100));
        assertThat(config.drainTimeout()).isEqualTo(Duration.ofSeconds(30));
    }

    @Test
    @DisplayName("REQ-LAB-009 REQ-LAB-010 nonsense sizes and waits are refused")
    void refusesNonsense() {
        Duration ms = Duration.ofMillis(1);
        assertThatThrownBy(() -> new EngineConfig(null, 1, 1, ms, ms)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new EngineConfig(ThreadMode.PLATFORM, 0, 1, ms, ms))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new EngineConfig(ThreadMode.PLATFORM, 1, 0, ms, ms))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new EngineConfig(ThreadMode.PLATFORM, 1, 1, Duration.ofMillis(-1), ms))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new EngineConfig(ThreadMode.PLATFORM, 1, 1, ms, Duration.ofMillis(-1)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
