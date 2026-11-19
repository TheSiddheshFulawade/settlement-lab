package com.siddheshfulawade.settlementlab.api;

import com.siddheshfulawade.settlementlab.domain.Outcome;
import com.siddheshfulawade.settlementlab.domain.Transfer;

/** One processed transfer, as streamed to subscribers. {@code amount} is in minor units. */
public record OutcomeEvent(String id, Outcome outcome, long source, long target, long amount) {

    static OutcomeEvent of(Transfer transfer, Outcome outcome) {
        return new OutcomeEvent(
                transfer.id().value(),
                outcome,
                transfer.source().value(),
                transfer.target().value(),
                transfer.amount().minorUnits());
    }
}
