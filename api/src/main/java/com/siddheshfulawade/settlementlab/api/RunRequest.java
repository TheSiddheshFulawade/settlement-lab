package com.siddheshfulawade.settlementlab.api;

import com.siddheshfulawade.settlementlab.pipeline.ThreadMode;

/**
 * What a visitor may choose for a run (REQ-RUN-002). Everything else (accounts, workers, seed) is fixed
 * by the server, so a visitor cannot ask for more work than the limits allow.
 *
 * <p>Invariant: a RunRequest that exists is valid. The ranges are checked here, in the constructor, so
 * no code path can build a run from an unchecked one.
 */
record RunRequest(LedgerChoice ledger, ThreadMode threads, FaultChoice faults, int transfers, int duplicatePercent) {

    static final int MAX_TRANSFERS = 20_000;
    static final int MAX_DUPLICATE_PERCENT = 10;

    RunRequest {
        if (ledger == null || threads == null || faults == null) {
            throw new InvalidRunRequestException("ledger, threads and faults are required");
        }
        if (faults == FaultChoice.DUPLICATE_ACKS) {
            throw new InvalidRunRequestException("faults must be NONE, FLAKY or OUTAGE");
        }
        if (transfers < 1 || transfers > MAX_TRANSFERS) {
            throw new InvalidRunRequestException("transfers must be 1 to " + MAX_TRANSFERS);
        }
        if (duplicatePercent < 0 || duplicatePercent > MAX_DUPLICATE_PERCENT) {
            throw new InvalidRunRequestException("duplicatePercent must be 0 to " + MAX_DUPLICATE_PERCENT);
        }
    }
}
