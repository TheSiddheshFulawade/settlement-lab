package com.siddheshfulawade.settlementlab.postgres;

import com.siddheshfulawade.settlementlab.domain.AccountId;
import com.siddheshfulawade.settlementlab.domain.Money;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;
import javax.sql.DataSource;

/** Test helper: empties the ledger tables and inserts the opening balances. */
final class LedgerSeed {

    private LedgerSeed() {}

    /**
     * Empties every ledger table and restarts the posting identity at 1 (a ledger's first posting
     * has sequence 1, as in the in-memory ledgers), then inserts one account row per opening balance.
     * Safe to call once per jqwik try: the schema itself is created once.
     */
    static void reset(DataSource dataSource, Map<AccountId, Money> openingBalances) {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try (Statement statement = connection.createStatement()) {
                statement.execute("TRUNCATE outbox, posting, transfer, account RESTART IDENTITY");
            }
            try (PreparedStatement insert =
                    connection.prepareStatement("INSERT INTO account (id, balance) VALUES (?, ?)")) {
                for (Map.Entry<AccountId, Money> entry : openingBalances.entrySet()) {
                    insert.setLong(1, entry.getKey().value());
                    insert.setLong(2, entry.getValue().minorUnits());
                    insert.addBatch();
                }
                insert.executeBatch();
            }
            connection.commit();
        } catch (SQLException e) {
            throw new IllegalStateException("could not seed the test ledger", e);
        }
    }
}
