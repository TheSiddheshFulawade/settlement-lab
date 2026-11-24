package com.siddheshfulawade.settlementlab.api;

/** A run request that is malformed or out of range. The message is written for the client: no library text. */
final class InvalidRunRequestException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    InvalidRunRequestException(String message) {
        super(message, null, false, false);
    }
}
