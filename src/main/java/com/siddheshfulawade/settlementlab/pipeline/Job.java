package com.siddheshfulawade.settlementlab.pipeline;

import com.siddheshfulawade.settlementlab.domain.Transfer;
import com.siddheshfulawade.settlementlab.idempotency.IdempotencyStore;

/** A queued transfer with the idempotency claim whose future the worker must complete. */
record Job(Transfer transfer, IdempotencyStore.Claim claim) {}
