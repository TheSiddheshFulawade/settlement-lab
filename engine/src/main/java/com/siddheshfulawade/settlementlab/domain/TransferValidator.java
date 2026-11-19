package com.siddheshfulawade.settlementlab.domain;

import java.util.Optional;
import java.util.function.Predicate;

/**
 * The rules a transfer must meet before it touches any balance (REQ-LAB-004). Used by the ledger,
 * and by the engine before it queues anything (L-05).
 */
public final class TransferValidator {

    private TransferValidator() {}

    /** Returns the first broken rule, or empty when the transfer is valid. */
    public static Optional<InvalidReason> validate(Transfer transfer, Predicate<AccountId> accountExists) {
        if (!transfer.amount().isPositive()) {
            return Optional.of(InvalidReason.NON_POSITIVE_AMOUNT);
        }
        if (transfer.source().equals(transfer.target())) {
            return Optional.of(InvalidReason.SAME_ACCOUNT);
        }
        if (!accountExists.test(transfer.source())) {
            return Optional.of(InvalidReason.UNKNOWN_SOURCE);
        }
        if (!accountExists.test(transfer.target())) {
            return Optional.of(InvalidReason.UNKNOWN_TARGET);
        }
        return Optional.empty();
    }
}
