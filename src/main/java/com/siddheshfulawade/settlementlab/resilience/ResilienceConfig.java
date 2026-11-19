package com.siddheshfulawade.settlementlab.resilience;

import java.time.Duration;
import java.util.Objects;

/**
 * Retry and circuit-breaker settings (SPEC.md section 4.4).
 *
 * @param maxAttempts calls per publish, the first included
 * @param initialBackoff wait before the first retry; each later wait is multiplied
 * @param backoffMultiplier growth of the wait per retry (exponential back-off)
 * @param randomizationFactor jitter: each wait is spread randomly by this fraction, so many callers
 *     do not retry at the same instant
 * @param slidingWindowSize how many recent calls the breaker judges
 * @param minimumCalls calls needed before the breaker may open
 * @param failureRatePercent failure rate in the window that opens the breaker
 * @param openWait how long the breaker stays open before letting probe calls through
 * @param halfOpenCalls probe calls allowed while half-open
 */
public record ResilienceConfig(
        int maxAttempts,
        Duration initialBackoff,
        double backoffMultiplier,
        double randomizationFactor,
        int slidingWindowSize,
        int minimumCalls,
        int failureRatePercent,
        Duration openWait,
        int halfOpenCalls) {

    public ResilienceConfig {
        Objects.requireNonNull(initialBackoff, "initialBackoff");
        Objects.requireNonNull(openWait, "openWait");
        require(maxAttempts >= 1, "maxAttempts must be at least 1");
        require(initialBackoff.isPositive() && openWait.isPositive(), "waits must be positive");
        require(backoffMultiplier >= 1.0, "backoffMultiplier must be at least 1");
        require(randomizationFactor >= 0.0 && randomizationFactor < 1.0, "randomizationFactor must be in [0, 1)");
        require(slidingWindowSize >= 1 && minimumCalls >= 1 && halfOpenCalls >= 1, "counts must be at least 1");
        require(failureRatePercent >= 1 && failureRatePercent <= 100, "failureRatePercent must be 1 to 100");
    }

    /** Starting values: 3 attempts from 50 ms doubling with 50 % jitter; window 20, 50 %, open 2 s. */
    public static ResilienceConfig defaults() {
        return new ResilienceConfig(3, Duration.ofMillis(50), 2.0, 0.5, 20, 10, 50, Duration.ofSeconds(2), 3);
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalArgumentException(message);
        }
    }
}
