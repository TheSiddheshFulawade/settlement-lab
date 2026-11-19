package com.siddheshfulawade.settlementlab.domain;

import java.util.List;
import java.util.Map;

/** Holds balances and applies transfers. Implementations must be safe for concurrent use. */
public interface Ledger {

    /**
     * Applies a transfer atomically: debit and credit both happen or neither does (REQ-LAB-002).
     *
     * @return the outcome ({@link Outcome#POSTED}, {@link Outcome#REJECTED_FUNDS} or
     *     {@link Outcome#REJECTED_INVALID}) and, when posted, the posting that was recorded
     */
    ApplyResult applyWithResult(Transfer transfer);

    /** {@link #applyWithResult} when only the outcome matters. */
    default Outcome apply(Transfer transfer) {
        return applyWithResult(transfer).outcome();
    }

    boolean hasAccount(AccountId id);

    /**
     * A consistent copy of every balance, ordered by account id: no transfer is half-visible in it,
     * so its total always equals the opening total (REQ-LAB-005).
     */
    Map<AccountId, Money> snapshot();

    /** Every posting so far, in sequence order. */
    List<Posting> postings();
}
