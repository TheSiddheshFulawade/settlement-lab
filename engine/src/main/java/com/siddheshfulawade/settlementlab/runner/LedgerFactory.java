package com.siddheshfulawade.settlementlab.runner;

import com.siddheshfulawade.settlementlab.domain.AccountId;
import com.siddheshfulawade.settlementlab.domain.Ledger;
import com.siddheshfulawade.settlementlab.domain.Money;
import com.siddheshfulawade.settlementlab.gl.GeneralLedgerPort;
import java.util.Map;
import java.util.Optional;

/**
 * A caller-supplied ledger for the runner. The database cells live in ledger-postgres because the
 * engine must not import JDBC.
 */
public interface LedgerFactory {

    /** The name written in the results row's ledger column. */
    String name();

    /** Builds a fresh ledger holding exactly the opening balances. */
    Ledger create(Map<AccountId, Money> opening);

    /**
     * Present when the ledger delivers postings to the general ledger itself; then the runner gives
     * the engine no in-memory outbox. Called after {@link #create}.
     */
    default Optional<GlDelivery> deliveryTo(GeneralLedgerPort gl) {
        return Optional.empty();
    }
}
