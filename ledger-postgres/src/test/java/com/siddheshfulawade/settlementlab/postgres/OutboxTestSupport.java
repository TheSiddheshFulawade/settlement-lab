package com.siddheshfulawade.settlementlab.postgres;

import static com.siddheshfulawade.settlementlab.domain.Fixtures.transfer;

import com.siddheshfulawade.settlementlab.domain.AccountId;
import com.siddheshfulawade.settlementlab.domain.Money;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;

/** Test helper for the outbox suites: seeding, posting N transfers, SQL counts and a second pool. */
final class OutboxTestSupport {

    static final AccountId[] ACCOUNTS = {AccountId.of(1), AccountId.of(2), AccountId.of(3), AccountId.of(4)};

    private OutboxTestSupport() {}

    static Map<AccountId, Money> openingBalances() {
        Map<AccountId, Money> opening = new LinkedHashMap<>();
        for (AccountId id : ACCOUNTS) {
            opening.put(id, Money.of(1_000_000));
        }
        return opening;
    }

    /** Seeds four rich accounts and posts {@code count} transfers (all POSTED) one after another. */
    static PostgresLedger seedAndPost(DataSource dataSource, LockMode mode, int count) {
        LedgerSeed.reset(dataSource, openingBalances());
        PostgresLedger ledger = new PostgresLedger(dataSource, mode);
        for (int i = 0; i < count; i++) {
            ledger.apply(transfer("t-" + i, ACCOUNTS[i % 4], ACCOUNTS[(i + 1) % 4], 1 + i % 7));
        }
        return ledger;
    }

    /** Row count of {@code fromClause}, e.g. a table name or a join with a WHERE. */
    static long count(DataSource dataSource, String fromClause) {
        return Long.parseLong(
                rows(dataSource, "SELECT count(*) FROM " + fromClause).get(0));
    }

    static List<String> rows(DataSource dataSource, String query) {
        List<String> out = new ArrayList<>();
        try (Connection c = dataSource.getConnection();
                Statement s = c.createStatement();
                ResultSet rs = s.executeQuery(query)) {
            while (rs.next()) {
                out.add(rs.getString(1));
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
        return out;
    }

    /** REQ-DB-006: postings with no outbox row (before any relay ran). Must be zero. */
    static long postingsWithoutOutbox(DataSource dataSource) {
        return count(dataSource, "posting p LEFT JOIN outbox o ON o.posting_seq = p.seq WHERE o.posting_seq IS NULL");
    }

    /** Outbox rows whose posting does not exist. Always zero (foreign key), asserted anyway. */
    static long outboxWithoutPosting(DataSource dataSource) {
        return count(dataSource, "outbox o LEFT JOIN posting p ON p.seq = o.posting_seq WHERE p.seq IS NULL");
    }

    /** A second pool on the same database: a new "process" after a restart. */
    static HikariDataSource newPoolOnSameDatabase(DataSource existing, int size) {
        HikariDataSource base = (HikariDataSource) existing;
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(base.getJdbcUrl());
        config.setUsername(base.getUsername());
        config.setPassword(base.getPassword());
        config.setMaximumPoolSize(size);
        return new HikariDataSource(config);
    }
}
