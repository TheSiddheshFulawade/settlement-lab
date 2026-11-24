package com.siddheshfulawade.settlementlab.api;

/**
 * One {@code metrics} event (api-contract section 2). Counts are running totals; {@code rejected} is funds,
 * invalid, queue-full and contention refusals, so {@code submitted} = posted + rejected + duplicates + still
 * in flight.
 */
record RunMetrics(
        long elapsedMs,
        long submitted,
        long posted,
        long rejected,
        long duplicates,
        long throughputPerSecond,
        long p99Micros,
        int queueDepth,
        int queueCapacity,
        long glRetries,
        String breakerState,
        long outboxSize) {}
