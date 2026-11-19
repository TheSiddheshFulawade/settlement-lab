package com.siddheshfulawade.settlementlab.domain;

import java.util.Objects;

/**
 * A request to move {@code amount} from {@code source} to {@code target}. Only nulls are refused
 * here; business validity is checked by {@link TransferValidator} so it can be rejected with a
 * reason (REQ-LAB-004).
 */
public record Transfer(TransferId id, AccountId source, AccountId target, Money amount) {

    public Transfer {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(amount, "amount");
    }
}
