package com.siddheshfulawade.settlementlab.api;

/** A run already has its maximum number of event subscribers (REQ-RUN-011). */
final class SubscriberLimitException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    SubscriberLimitException() {
        super("run has the maximum number of event subscribers", null, false, false);
    }
}
