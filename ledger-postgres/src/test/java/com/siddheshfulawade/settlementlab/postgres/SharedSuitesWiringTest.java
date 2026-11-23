package com.siddheshfulawade.settlementlab.postgres;

import com.siddheshfulawade.settlementlab.domain.AccountId;
import com.siddheshfulawade.settlementlab.domain.Ledger;
import com.siddheshfulawade.settlementlab.domain.LedgerPropertiesContract;
import com.siddheshfulawade.settlementlab.domain.LockingLedger;
import com.siddheshfulawade.settlementlab.domain.Money;
import java.util.Map;

/**
 * Proves the engine test-jar is consumable from this module. Uses the in-memory LockingLedger, so it
 * needs no Docker. V3-03 replaces it with PostgresLedger subclasses of the same contracts.
 */
class SharedSuitesWiringTest extends LedgerPropertiesContract {

    @Override
    protected Ledger newLedger(Map<AccountId, Money> openingBalances) {
        return new LockingLedger(openingBalances);
    }
}
