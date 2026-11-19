package com.siddheshfulawade.settlementlab.runner;

/** One row of the results table (REQ-LAB-015). Latencies are end to end, submit to answer. */
public record ScenarioResult(
        String ledger,
        String threads,
        String faults,
        long transfers,
        long throughputPerSecond,
        long p50Micros,
        long p99Micros,
        long rejected,
        long duplicates,
        long retries,
        long breakerOpens,
        long outboxPeak,
        boolean invariantsPassed,
        String firstFailure) {}
