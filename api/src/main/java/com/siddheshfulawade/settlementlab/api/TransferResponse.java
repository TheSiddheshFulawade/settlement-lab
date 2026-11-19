package com.siddheshfulawade.settlementlab.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.siddheshfulawade.settlementlab.domain.Outcome;

/** The answer to a POST: the transfer id, its outcome, and the reason when it was invalid. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record TransferResponse(String id, Outcome outcome, String reason) {}
