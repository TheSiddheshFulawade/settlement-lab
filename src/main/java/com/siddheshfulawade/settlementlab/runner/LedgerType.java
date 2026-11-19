package com.siddheshfulawade.settlementlab.runner;

import com.siddheshfulawade.settlementlab.domain.AccountId;
import com.siddheshfulawade.settlementlab.domain.CasLedger;
import com.siddheshfulawade.settlementlab.domain.Ledger;
import com.siddheshfulawade.settlementlab.domain.LockingLedger;
import com.siddheshfulawade.settlementlab.domain.Money;
import java.util.Map;

/** The ledger column of the matrix. */
public enum LedgerType {
    LOCKING,
    CAS;

    Ledger create(Map<AccountId, Money> opening) {
        return this == LOCKING ? new LockingLedger(opening) : new CasLedger(opening);
    }
}
