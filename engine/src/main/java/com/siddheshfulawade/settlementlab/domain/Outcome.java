package com.siddheshfulawade.settlementlab.domain;

/** The result of one submitted transfer. */
public enum Outcome {
    /** Debited and credited; a {@link Posting} was recorded. */
    POSTED,
    /** The source balance was too low; nothing changed (REQ-LAB-003). */
    REJECTED_FUNDS,
    /** The transfer broke a rule in {@link InvalidReason}; nothing changed (REQ-LAB-004). */
    REJECTED_INVALID,
    /** The intake queue was full; the transfer was not processed (REQ-LAB-009). */
    QUEUE_FULL,
    /**
     * The ledger gave up after repeated optimistic conflicts; nothing was stored. The id is released,
     * so a retry with the same id is processed fresh (REQ-DB-004a).
     */
    REJECTED_CONTENTION,
    /** The same transfer id was already seen (REQ-LAB-008). Counted in metrics. */
    DUPLICATE
}
