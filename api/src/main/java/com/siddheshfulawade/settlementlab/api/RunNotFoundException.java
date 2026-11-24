package com.siddheshfulawade.settlementlab.api;

/** The run id is unknown, malformed, or past its retention. */
final class RunNotFoundException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    RunNotFoundException() {
        super("run not found", null, false, false);
    }
}
