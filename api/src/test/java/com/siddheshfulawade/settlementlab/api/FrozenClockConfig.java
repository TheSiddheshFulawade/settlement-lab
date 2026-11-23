package com.siddheshfulawade.settlementlab.api;

import java.time.Instant;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * Like {@link FakeClockConfig}, but the clock stands still until a test moves it, so a Retry-After value
 * can be compared exactly: with a running clock a few real seconds pass between the first start and the
 * refusal, and the exact number changes under the test. Deadlines then fire only when the test advances the
 * clock; a test that holds a run open must advance it past the deadline to let the run end.
 */
@TestConfiguration(proxyBeanMethods = false)
class FrozenClockConfig {

    @Bean
    @Primary
    MutableClock testRunClock() {
        return MutableClock.frozen(Instant.parse("2026-12-01T10:00:00Z"));
    }
}
