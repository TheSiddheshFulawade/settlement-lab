package com.siddheshfulawade.settlementlab.domain;

import java.util.Locale;

/**
 * An amount in minor units (for example cents), held as a {@code long}: exact, with no floating
 * point anywhere (REQ-LAB-001). Arithmetic throws on overflow instead of wrapping around.
 *
 * <p>Money itself may be zero or negative, so that an invalid request can still be represented and
 * rejected with a reason. "No balance below zero" is the ledger's rule, not this type's.
 */
public record Money(long minorUnits) {

    public static final Money ZERO = new Money(0);

    public static Money of(long minorUnits) {
        return new Money(minorUnits);
    }

    public Money plus(Money other) {
        return new Money(Math.addExact(minorUnits, other.minorUnits));
    }

    public Money minus(Money other) {
        return new Money(Math.subtractExact(minorUnits, other.minorUnits));
    }

    public boolean isPositive() {
        return minorUnits > 0;
    }

    /** International format, for example {@code 1,234,567}. */
    @Override
    public String toString() {
        return String.format(Locale.ROOT, "%,d", minorUnits);
    }
}
