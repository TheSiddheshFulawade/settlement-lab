package com.siddheshfulawade.settlementlab.api;

/** Which fault profile the fake general ledger uses ({@code settlement.faults}). */
public enum FaultChoice {
    NONE,
    FLAKY,
    DUPLICATE_ACKS,
    /** Refuses everything from 30 s after start for 10 s: long enough to watch the breaker open. */
    OUTAGE
}
