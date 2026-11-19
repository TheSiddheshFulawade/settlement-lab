package com.siddheshfulawade.settlementlab.api;

import com.siddheshfulawade.settlementlab.domain.AccountId;
import com.siddheshfulawade.settlementlab.domain.Money;
import com.siddheshfulawade.settlementlab.domain.Transfer;
import com.siddheshfulawade.settlementlab.domain.TransferId;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * The POST body. Only the shape is checked here (REQ-API-003); business rules such as a positive
 * amount or a known account stay in the engine, which answers {@code REJECTED_INVALID} with a reason.
 *
 * @param amount minor units (for example cents), never a decimal
 */
public record TransferRequest(
        @NotBlank String id,
        @NotNull Long source,
        @NotNull Long target,
        @NotNull Long amount) {

    Transfer toTransfer() {
        return new Transfer(TransferId.of(id), AccountId.of(source), AccountId.of(target), Money.of(amount));
    }
}
