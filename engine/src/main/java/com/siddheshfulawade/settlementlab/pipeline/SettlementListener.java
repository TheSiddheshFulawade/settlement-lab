package com.siddheshfulawade.settlementlab.pipeline;

import com.siddheshfulawade.settlementlab.domain.Outcome;
import com.siddheshfulawade.settlementlab.domain.Transfer;

/**
 * Hears the outcome of every transfer a worker processed (v2 turns these into server-sent events).
 * Called on a worker thread after the caller's future is complete, so keep it quick. If it throws,
 * the engine counts the error and carries on.
 */
@FunctionalInterface
public interface SettlementListener {

    SettlementListener NONE = (transfer, outcome) -> {};

    void onOutcome(Transfer transfer, Outcome outcome);
}
