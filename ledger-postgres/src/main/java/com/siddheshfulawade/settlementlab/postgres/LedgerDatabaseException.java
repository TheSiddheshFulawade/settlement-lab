package com.siddheshfulawade.settlementlab.postgres;

import java.sql.SQLException;

/**
 * A ledger call failed in the database and its transaction was rolled back, so nothing it did is
 * stored. The cause is the driver's {@link SQLException}; its SQLState says why, for example
 * {@code 55P03} (lock_timeout) or {@code 57014} (statement_timeout). The same transfer id may be
 * retried. Unchecked, because {@link com.siddheshfulawade.settlementlab.domain.Ledger} declares no
 * checked exceptions.
 */
public final class LedgerDatabaseException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public LedgerDatabaseException(String message, SQLException cause) {
        super(message, cause);
    }
}
