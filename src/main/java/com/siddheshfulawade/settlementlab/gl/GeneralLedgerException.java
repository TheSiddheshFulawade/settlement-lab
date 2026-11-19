package com.siddheshfulawade.settlementlab.gl;

/** A transient general-ledger failure: worth retrying later. */
public final class GeneralLedgerException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public enum Kind {
        /** Refused; nothing stored. */
        ERROR,
        /** No answer in time. The entry may have been stored (lost ack). */
        TIMEOUT,
        /** Unavailable for a period; nothing stored. */
        OUTAGE,
        /** Not called at all: our circuit breaker is open and fails fast. Nothing stored. */
        CIRCUIT_OPEN
    }

    private final Kind kind;

    public GeneralLedgerException(Kind kind, String message) {
        super(message);
        this.kind = kind;
    }

    public Kind kind() {
        return kind;
    }
}
