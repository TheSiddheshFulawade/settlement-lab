package com.siddheshfulawade.settlementlab.postgres;

/** How {@link PostgresLedger} stops two transfers on the same account from overwriting each other. */
public enum LockMode {
    /**
     * Row locks: {@code SELECT ... FOR UPDATE} on both accounts, lower id first (REQ-DB-003). A second
     * transfer on the same account waits. Oracle: the same {@code SELECT ... FOR UPDATE}.
     */
    PESSIMISTIC,
    /**
     * Version column: {@code UPDATE ... WHERE id = ? AND version = ?}, retried when it changes zero rows
     * (REQ-DB-004). Nothing waits; a loser retries. Arrives in V3-05.
     */
    OPTIMISTIC
}
