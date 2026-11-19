package com.siddheshfulawade.settlementlab.pipeline;

import java.time.Duration;
import java.util.Objects;

/**
 * Engine settings.
 *
 * @param threadMode platform or virtual threads (REQ-LAB-010)
 * @param workers N: how many transfers may be applied at once, in either mode
 * @param queueCapacity how many accepted transfers may wait for a worker
 * @param offerTimeout how long {@code submit} waits for queue space before answering
 *     {@code QUEUE_FULL}; the bound that keeps callers from blocking forever (REQ-LAB-009)
 * @param drainTimeout how long {@code close} waits for queued work before giving up on it
 */
public record EngineConfig(
        ThreadMode threadMode, int workers, int queueCapacity, Duration offerTimeout, Duration drainTimeout) {

    public EngineConfig {
        Objects.requireNonNull(threadMode, "threadMode");
        Objects.requireNonNull(offerTimeout, "offerTimeout");
        Objects.requireNonNull(drainTimeout, "drainTimeout");
        if (workers < 1) {
            throw new IllegalArgumentException("workers must be at least 1, was " + workers);
        }
        if (queueCapacity < 1) {
            throw new IllegalArgumentException("queueCapacity must be at least 1, was " + queueCapacity);
        }
        if (offerTimeout.isNegative() || drainTimeout.isNegative()) {
            throw new IllegalArgumentException("timeouts must not be negative");
        }
    }

    /** A 100 ms offer wait and a 30 s drain on close. */
    public static EngineConfig of(ThreadMode threadMode, int workers, int queueCapacity) {
        return new EngineConfig(threadMode, workers, queueCapacity, Duration.ofMillis(100), Duration.ofSeconds(30));
    }
}
