package com.siddheshfulawade.settlementlab.domain;

import java.util.Map;

/** Runs the shared ledger stress contract against LockingLedger. */
class LockingLedgerStressTest extends LedgerStressContract {

    @Override
    protected Ledger newLedger(Map<AccountId, Money> openingBalances) {
        return new LockingLedger(openingBalances);
    }
}
