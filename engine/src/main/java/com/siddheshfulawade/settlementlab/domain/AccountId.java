package com.siddheshfulawade.settlementlab.domain;

/**
 * An account's identity. The natural order (by number) is the one global lock order that
 * {@link LockingLedger} uses, so it must never change (REQ-LAB-006).
 */
public record AccountId(long value) implements Comparable<AccountId> {

    public static AccountId of(long value) {
        return new AccountId(value);
    }

    @Override
    public int compareTo(AccountId other) {
        return Long.compare(value, other.value);
    }

    @Override
    public String toString() {
        return "acc-" + value;
    }
}
