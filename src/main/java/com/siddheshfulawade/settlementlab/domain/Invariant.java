package com.siddheshfulawade.settlementlab.domain;

/** The rules a run must never break, checked by {@link InvariantChecker} (REQ-LAB-014). */
public enum Invariant {
    /** The sum of all balances equals the opening total (REQ-LAB-005). */
    TOTAL_CONSERVED,
    /** No balance is below zero (REQ-LAB-003). */
    NO_NEGATIVE_BALANCE,
    /** No transfer id appears in more than one posting (REQ-LAB-008). */
    POSTED_AT_MOST_ONCE,
    /** Opening balances plus every posting give exactly the current balances (REQ-LAB-002). */
    POSTINGS_EXPLAIN_BALANCES,
    /** Every accepted transfer has a posting, and every posting was accepted. */
    POSTINGS_MATCH_ACCEPTED
}
