package com.siddheshfulawade.settlementlab.postgres;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.junit.jupiter.api.Test;

/** Schema rules from SPEC.md section 16.2, checked against a real PostgreSQL (REQ-DB-002, 011). */
class SchemaTest extends PostgresTestBase {

    private static final String CHECK_VIOLATION = "23514";
    private static final String UNIQUE_VIOLATION = "23505";
    private static final String FOREIGN_KEY_VIOLATION = "23503";

    /**
     * REQ-DB-011: an applied migration is never edited; a schema change is a new V2 file. If this
     * constant fails, someone edited V1__ledger.sql: revert that edit and add V2 instead.
     */
    private static final int V1_CHECKSUM = -1907851484;

    @Test
    void REQ_DB_002_migrationCreatesExactlyTheFourLedgerTables() throws SQLException {
        List<String> tables = column("SELECT table_name FROM information_schema.tables"
                + " WHERE table_schema = 'public' AND table_type = 'BASE TABLE' ORDER BY table_name");

        assertThat(tables).containsExactly("account", "flyway_schema_history", "outbox", "posting", "transfer");
    }

    @Test
    void REQ_DB_002_noColumnUsesAFractionalOrMoneyType() throws SQLException {
        List<String> bad = column("SELECT table_name || '.' || column_name || ':' || data_type"
                + " FROM information_schema.columns WHERE table_schema = 'public'"
                + " AND data_type IN ('numeric', 'decimal', 'real', 'double precision', 'money')");

        assertThat(bad).isEmpty();
    }

    @Test
    void REQ_DB_002_everyMoneyColumnIsBigint() throws SQLException {
        List<String> money = column("SELECT table_name || '.' || column_name || ':' || data_type"
                + " FROM information_schema.columns WHERE table_schema = 'public'"
                + " AND column_name IN ('balance', 'amount') ORDER BY 1");

        assertThat(money).containsExactly("account.balance:bigint", "posting.amount:bigint");
    }

    @Test
    void REQ_DB_002_negativeBalanceIsRejected() {
        assertThat(sqlStateOf("INSERT INTO account (id, balance) VALUES (1, -1)"))
                .isEqualTo(CHECK_VIOLATION);
    }

    @Test
    void REQ_DB_002_zeroBalanceIsAllowed() throws SQLException {
        exec("INSERT INTO account (id, balance) VALUES (1, 0)");

        assertThat(column("SELECT balance FROM account WHERE id = 1")).containsExactly("0");
    }

    @Test
    void REQ_DB_002_updateCannotDriveBalanceBelowZero() throws SQLException {
        exec("INSERT INTO account (id, balance) VALUES (1, 100)");

        assertThat(sqlStateOf("UPDATE account SET balance = balance - 101 WHERE id = 1"))
                .isEqualTo(CHECK_VIOLATION);
        assertThat(column("SELECT balance FROM account WHERE id = 1")).containsExactly("100");
    }

    @Test
    void REQ_DB_002_postingAmountMustBePositive() throws SQLException {
        exec("INSERT INTO transfer (id, outcome) VALUES ('t1', 'POSTED')");

        assertThat(sqlStateOf("INSERT INTO posting (transfer_id, source, target, amount) VALUES ('t1', 1, 2, 0)"))
                .isEqualTo(CHECK_VIOLATION);
    }

    @Test
    void accountVersionDefaultsToZero() throws SQLException {
        exec("INSERT INTO account (id, balance) VALUES (7, 500)");

        assertThat(column("SELECT version FROM account WHERE id = 7")).containsExactly("0");
    }

    @Test
    void duplicateTransferIdIsRejected() throws SQLException {
        exec("INSERT INTO transfer (id, outcome) VALUES ('t1', 'POSTED')");

        assertThat(sqlStateOf("INSERT INTO transfer (id, outcome) VALUES ('t1', 'REJECTED')"))
                .isEqualTo(UNIQUE_VIOLATION);
    }

    @Test
    void duplicatePostingForTheSameTransferIsRejected() throws SQLException {
        exec("INSERT INTO transfer (id, outcome) VALUES ('t1', 'POSTED')");
        exec("INSERT INTO posting (transfer_id, source, target, amount) VALUES ('t1', 1, 2, 10)");

        assertThat(sqlStateOf("INSERT INTO posting (transfer_id, source, target, amount) VALUES ('t1', 1, 2, 10)"))
                .isEqualTo(UNIQUE_VIOLATION);
    }

    @Test
    void postingSequenceIncreasesInInsertOrder() throws SQLException {
        for (String id : List.of("c", "a", "b")) {
            exec("INSERT INTO transfer (id, outcome) VALUES ('" + id + "', 'POSTED')");
            exec("INSERT INTO posting (transfer_id, source, target, amount) VALUES ('" + id + "', 1, 2, 1)");
        }

        // Insert order is c, a, b; ordering by seq must give the same order, not id order.
        assertThat(column("SELECT transfer_id FROM posting ORDER BY seq")).containsExactly("c", "a", "b");
        List<Long> seqs = column("SELECT seq FROM posting ORDER BY seq").stream()
                .map(Long::parseLong)
                .toList();
        assertThat(seqs).isSorted().doesNotHaveDuplicates();
    }

    @Test
    void outboxRowWithoutAPostingIsRejected() {
        assertThat(sqlStateOf("INSERT INTO outbox (posting_seq) VALUES (999)")).isEqualTo(FOREIGN_KEY_VIOLATION);
    }

    @Test
    void outboxRowDefaultsToZeroAttemptsAndDueNow() throws SQLException {
        exec("INSERT INTO transfer (id, outcome) VALUES ('t1', 'POSTED')");
        exec("INSERT INTO posting (transfer_id, source, target, amount) VALUES ('t1', 1, 2, 10)");
        exec("INSERT INTO outbox (posting_seq) SELECT seq FROM posting");

        assertThat(column("SELECT attempts FROM outbox")).containsExactly("0");
        assertThat(column("SELECT count(*) FROM outbox WHERE next_attempt_at IS NOT NULL"))
                .containsExactly("1");
    }

    @Test
    void migrateOnAnUpToDateSchemaAppliesNothing() {
        assertThat(LedgerSchema.migrate(dataSource())).isZero();
    }

    @Test
    void REQ_DB_011_appliedMigrationsPassFlywayValidation() {
        assertThatCode(flyway()::validate).doesNotThrowAnyException();
    }

    @Test
    void REQ_DB_011_versionOneChecksumNeverChanges() {
        MigrationInfo v1 = flyway().info().applied()[0];

        assertThat(v1.getVersion().getVersion()).isEqualTo("1");
        assertThat(v1.getChecksum()).isEqualTo(V1_CHECKSUM);
    }

    // TODO-ME: write `postingWithOutboxRowCannotBeDeleted`: insert a transfer, a posting and an
    //  outbox row for it, then DELETE the posting and assert the SQLState is FOREIGN_KEY_VIOLATION
    //  (23503): the outbox cannot outlive its posting (REQ-DB-006 relies on it). Hint: use the
    //  sqlStateOf helper. Check it: run `./mvnw verify -Pdb` (green); then drop the REFERENCES
    //  clause from outbox in a scratch copy of the SQL and see your test fail.

    private static Flyway flyway() {
        return Flyway.configure()
                .dataSource(dataSource())
                .locations("classpath:db/migration")
                .load();
    }

    private static void exec(String sql) throws SQLException {
        try (Connection c = dataSource().getConnection();
                PreparedStatement s = c.prepareStatement(sql)) {
            s.execute();
        }
    }

    /** Runs the statement and returns the SQLState it failed with, or null if it succeeded. */
    private static String sqlStateOf(String sql) {
        try {
            exec(sql);
            return null;
        } catch (SQLException e) {
            return e.getSQLState();
        }
    }

    private static List<String> column(String sql) throws SQLException {
        List<String> values = new ArrayList<>();
        try (Connection c = dataSource().getConnection();
                PreparedStatement s = c.prepareStatement(sql);
                ResultSet rs = s.executeQuery()) {
            while (rs.next()) {
                values.add(rs.getString(1));
            }
        }
        return values;
    }
}
