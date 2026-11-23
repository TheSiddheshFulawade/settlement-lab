package com.siddheshfulawade.settlementlab.postgres;

import com.siddheshfulawade.settlementlab.domain.AccountId;
import com.siddheshfulawade.settlementlab.domain.Ledger;
import com.siddheshfulawade.settlementlab.domain.LedgerStressContract;
import com.siddheshfulawade.settlementlab.domain.LockingLedger;
import com.siddheshfulawade.settlementlab.domain.Money;
import java.util.Map;

/** Same as {@link SharedSuitesWiringTest}, for the stress contract. */
class SharedStressWiringTest extends LedgerStressContract {

    @Override
    protected Ledger newLedger(Map<AccountId, Money> openingBalances) {
        return new LockingLedger(openingBalances);
    }
}
