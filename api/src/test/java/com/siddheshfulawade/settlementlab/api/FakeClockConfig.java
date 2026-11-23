package com.siddheshfulawade.settlementlab.api;

import java.time.Instant;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * Replaces the clock the run service reads, so a test can jump the rate-limit window and the report
 * retention by hand. ASSUMED: the production {@code RunService} bean takes a {@code java.time.Clock}.
 */
@TestConfiguration(proxyBeanMethods = false)
class FakeClockConfig {

    @Bean
    @Primary
    MutableClock testRunClock() {
        return MutableClock.running(Instant.parse("2026-12-01T10:00:00Z"));
    }
}
