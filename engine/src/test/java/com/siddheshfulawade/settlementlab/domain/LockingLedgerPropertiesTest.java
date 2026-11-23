package com.siddheshfulawade.settlementlab.domain;

import java.util.Map;

/** Runs the shared ledger properties contract against LockingLedger. */
class LockingLedgerPropertiesTest extends LedgerPropertiesContract {

    @Override
    protected Ledger newLedger(Map<AccountId, Money> openingBalances) {
        return new LockingLedger(openingBalances);
    }
}
