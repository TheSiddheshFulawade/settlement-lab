package com.siddheshfulawade.settlementlab.runner;

import com.siddheshfulawade.settlementlab.resilience.ResilienceConfig;
import java.time.Duration;

/**
 * Engine and harness settings shared by every cell.
 *
 * @param workers N, the concurrency cap in both thread modes
 * @param clients closed-loop callers: each submits, waits for the answer, then submits the next
 */
public record RunnerSettings(
        int workers,
        int queueCapacity,
        int clients,
        Duration offerTimeout,
        ResilienceConfig resilience,
        Duration outboxRetry,
        Duration drainTimeout) {

    public static RunnerSettings defaults() {
        int cores = Runtime.getRuntime().availableProcessors();
        return new RunnerSettings(
                Math.max(4, cores * 2),
                1_024,
                64,
                Duration.ofMillis(100),
                ResilienceConfig.defaults(),
                Duration.ofMillis(20),
                Duration.ofMinutes(2));
    }

    /** Small waits, for tests. */
    public static RunnerSettings quick() {
        return new RunnerSettings(
                4,
                256,
                16,
                Duration.ofMillis(100),
                new ResilienceConfig(3, Duration.ofMillis(1), 2.0, 0.5, 20, 10, 50, Duration.ofMillis(100), 3),
                Duration.ofMillis(5),
                Duration.ofSeconds(30));
    }
}
