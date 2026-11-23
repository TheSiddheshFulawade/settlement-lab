package com.siddheshfulawade.settlementlab.api;

/** The run service is closed: the application is shutting down. */
final class ServiceClosedException extends IllegalStateException {

    private static final long serialVersionUID = 1L;

    ServiceClosedException() {
        super("run service is closed");
    }
}
