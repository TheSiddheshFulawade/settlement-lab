package com.siddheshfulawade.settlementlab.postgres;

import javax.sql.DataSource;
import org.flywaydb.core.Flyway;

/**
 * Creates and upgrades the ledger schema with Flyway. The only way the schema changes (REQ-DB-011):
 * every change is a new {@code V<n>__<what>.sql} under {@code db/migration}, never an edit of an
 * applied one.
 */
public final class LedgerSchema {

    private LedgerSchema() {}

    /**
     * Applies every pending migration and returns how many it applied (0 when the schema is current).
     *
     * <p>Invariant: {@code cleanDisabled} stays true, which is Flyway's default, so production code can
     * never erase the ledger by calling {@code clean}. Only the test base turns it on, to reset a
     * throwaway container database between tests.
     *
     * <p>Oracle equivalent: Flyway runs the same way against Oracle; only the SQL dialect and the
     * database module differ.
     */
    public static int migrate(DataSource dataSource) {
        return Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .cleanDisabled(true)
                .load()
                .migrate()
                .migrationsExecuted;
    }
}
