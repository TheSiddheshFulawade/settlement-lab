package com.siddheshfulawade.settlementlab.api;

import com.siddheshfulawade.settlementlab.domain.AccountId;
import com.siddheshfulawade.settlementlab.domain.Money;
import com.siddheshfulawade.settlementlab.domain.Transfer;
import com.siddheshfulawade.settlementlab.domain.TransferId;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/**
 * The POST body. Only the shape is checked here (REQ-API-003); business rules such as a positive
 * amount or a known account stay in the engine, which answers {@code REJECTED_INVALID} with a reason.
 *
 * <p>An id is 1 to 64 ASCII letters, digits, dots, underscores or hyphens (REQ-API-009): it ends up in the
 * response, the event stream and the idempotency store, so nothing a client can use to forge a line or a
 * log entry is allowed. {@code @Pattern} must match the whole string, so a trailing newline is refused too.
 *
 * @param amount minor units (for example cents), never a decimal
 */
public record TransferRequest(
        @NotBlank @Pattern(regexp = ID_PATTERN) String id,
        @NotNull Long source,
        @NotNull Long target,
        @NotNull Long amount) {

    static final String ID_PATTERN = "[A-Za-z0-9._-]{1,64}";

    Transfer toTransfer() {
        return new Transfer(TransferId.of(id), AccountId.of(source), AccountId.of(target), Money.of(amount));
    }
}
