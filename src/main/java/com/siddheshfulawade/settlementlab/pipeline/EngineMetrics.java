package com.siddheshfulawade.settlementlab.pipeline;

/**
 * Counters since the engine started. {@code duplicates} counts submissions whose id was already
 * claimed; those callers still received the original outcome (REQ-LAB-008).
 */
public record EngineMetrics(
        long submitted,
        long posted,
        long rejectedFunds,
        long rejectedInvalid,
        long queueFull,
        long duplicates,
        long failed,
        long listenerErrors,
        int queueDepth) {}
