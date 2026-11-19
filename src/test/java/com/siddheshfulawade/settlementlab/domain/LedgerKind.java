package com.siddheshfulawade.settlementlab.domain;

import java.util.Map;
import java.util.function.Function;

/**
 * Every {@link Ledger} implementation under test. The property and stress suites run once per
 * constant, so a new ledger (CasLedger in L-03) gets the same proofs by adding one line here.
 */
enum LedgerKind {
    LOCKING(LockingLedger::new);

    private final Function<Map<AccountId, Money>, Ledger> factory;

    LedgerKind(Function<Map<AccountId, Money>, Ledger> factory) {
        this.factory = factory;
    }

    Ledger create(Map<AccountId, Money> openingBalances) {
        return factory.apply(openingBalances);
    }
}
