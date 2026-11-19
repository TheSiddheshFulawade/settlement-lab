package com.siddheshfulawade.settlementlab.domain;

import java.util.Objects;

/** The caller's key for one transfer. The same id means the same transfer (REQ-LAB-008). */
public record TransferId(String value) {

    public TransferId {
        Objects.requireNonNull(value, "value");
        if (value.isBlank()) {
            throw new IllegalArgumentException("transfer id must not be blank");
        }
    }

    public static TransferId of(String value) {
        return new TransferId(value);
    }

    @Override
    public String toString() {
        return value;
    }
}
