package com.siddheshfulawade.settlementlab.api;

/**
 * A start was refused by a limit. The message names the limit only: never the visitor's address, so
 * nothing that logs or prints this exception can leak one.
 */
final class RunRefusedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final RunLimits.Limit limit;
    private final long retryAfterSeconds;

    RunRefusedException(RunLimits.Limit limit, long retryAfterSeconds) {
        super("run refused by " + limit + " limit", null, false, false); // no stack trace needed
        this.limit = limit;
        this.retryAfterSeconds = retryAfterSeconds;
    }

    RunLimits.Limit limit() {
        return limit;
    }

    long retryAfterSeconds() {
        return retryAfterSeconds;
    }
}
