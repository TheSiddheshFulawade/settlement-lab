package com.siddheshfulawade.settlementlab.domain;

import java.util.Objects;
import java.util.Optional;

/**
 * What a ledger did with one transfer: the outcome and, only when it is {@link Outcome#POSTED}, the
 * posting it recorded. The ledger stays the single source of postings, sequence numbers included,
 * so what goes to the general ledger is exactly what the ledger holds (REQ-LAB-011, 012).
 */
public record ApplyResult(Outcome outcome, Optional<Posting> posting) {

    public ApplyResult {
        Objects.requireNonNull(outcome, "outcome");
        Objects.requireNonNull(posting, "posting");
        if ((outcome == Outcome.POSTED) != posting.isPresent()) {
            throw new IllegalArgumentException("a posting must be present exactly when the outcome is POSTED");
        }
        if (outcome != Outcome.POSTED && outcome != Outcome.REJECTED_FUNDS && outcome != Outcome.REJECTED_INVALID) {
            throw new IllegalArgumentException("a ledger never answers " + outcome);
        }
    }

    public static ApplyResult posted(Posting posting) {
        return new ApplyResult(Outcome.POSTED, Optional.of(posting));
    }

    public static ApplyResult rejected(Outcome outcome) {
        return new ApplyResult(outcome, Optional.empty());
    }
}
