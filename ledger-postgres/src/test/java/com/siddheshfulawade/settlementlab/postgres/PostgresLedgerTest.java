package com.siddheshfulawade.settlementlab.postgres;

import static com.siddheshfulawade.settlementlab.domain.Fixtures.A;
import static com.siddheshfulawade.settlementlab.domain.Fixtures.B;
import static com.siddheshfulawade.settlementlab.domain.Fixtures.C;
import static com.siddheshfulawade.settlementlab.domain.Fixtures.transfer;
import static org.assertj.core.api.Assertions.assertThat;

import com.siddheshfulawade.settlementlab.domain.AccountId;
import com.siddheshfulawade.settlementlab.domain.ApplyResult;
import com.siddheshfulawade.settlementlab.domain.Fixtures;
import com.siddheshfulawade.settlementlab.domain.Money;
import com.siddheshfulawade.settlementlab.domain.Outcome;
import com.siddheshfulawade.settlementlab.domain.Posting;
import com.siddheshfulawade.settlementlab.domain.TransferId;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

/**
 * The single-threaded behaviour of PostgresLedger (pessimistic), checked against the real tables
 * (SPEC.md 16.2, 16.3).
 *
 * <p>Implementer note on the {@code transfer} table: these tests REQUIRE the step-1 row of 16.3 for
 * POSTED and REJECTED_FUNDS (outcome stored, posting_seq set only when posted). They do not test
 * duplicates: V3-04 adds that. For V3-03 a fresh id per transfer is enough, but the row must be
 * written now so V3-04 only adds the conflict handling. For REJECTED_INVALID only "no balance or
 * posting changes" is asserted here; that an unknown account stores REJECTED_INVALID on its transfer
 * row while validator rejections store nothing is PostgresIdempotencyTest's job. The outbox row (REQ-DB-006)
 * is V3-06 and is not required here, except that a rejection writes none.
 */
class PostgresLedgerTest extends PostgresTestBase {

    private PostgresLedger ledgerWith(Map<AccountId, Money> opening) {
        LedgerSeed.reset(dataSource(), opening);
        return new PostgresLedger(dataSource(), LockMode.PESSIMISTIC);
    }

    @Test
    void REQ_DB_001_posted_moves_money_and_writes_one_posting_and_one_transfer_row() throws SQLException {
        PostgresLedger ledger = ledgerWith(Map.of(A, Money.of(100), B, Money.of(50)));

        ApplyResult result = ledger.applyWithResult(transfer("t-1", A, B, 30));

        assertThat(result.outcome()).isEqualTo(Outcome.POSTED);
        assertThat(ledger.balance(A)).contains(Money.of(70));
        assertThat(ledger.balance(B)).contains(Money.of(80));
        assertThat(rows("SELECT transfer_id || ',' || source || ',' || target || ',' || amount FROM posting"))
                .containsExactly("t-1,1,2,30");
        long seq = Long.parseLong(rows("SELECT seq FROM posting").get(0));
        assertThat(seq).as("first posting of a fresh ledger").isEqualTo(1);
        assertThat(rows("SELECT id || ',' || outcome || ',' || coalesce(posting_seq::text, 'null') FROM transfer"))
                .containsExactly("t-1,POSTED," + seq);
        assertThat(result.posting()).contains(new Posting(seq, TransferId.of("t-1"), A, B, Money.of(30)));
    }

    @Test
    void REQ_DB_001_postings_are_numbered_in_commit_order() {
        PostgresLedger ledger = ledgerWith(Fixtures.uniformAccounts(3, 100));

        ledger.apply(transfer("t-1", A, B, 1));
        ledger.apply(transfer("t-2", B, C, 1));
        ledger.apply(transfer("t-3", C, A, 1));

        assertThat(ledger.postings()).extracting(p -> p.transferId().value()).containsExactly("t-1", "t-2", "t-3");
        assertThat(ledger.postings()).extracting(Posting::sequence).isSorted();
    }

    @Test
    void REQ_DB_001_one_more_than_the_balance_is_rejected_for_funds_and_less_is_posted() {
        PostgresLedger ledger = ledgerWith(Map.of(A, Money.of(10), B, Money.of(0)));

        assertThat(ledger.apply(transfer("over", A, B, 11))).isEqualTo(Outcome.REJECTED_FUNDS);
        assertThat(ledger.apply(transfer("fits", A, B, 9))).isEqualTo(Outcome.POSTED);
        assertThat(ledger.balance(A)).contains(Money.of(1));
    }

    @Test
    void REQ_DB_001_rejected_for_funds_changes_no_balance_and_stores_only_the_transfer_outcome() throws SQLException {
        PostgresLedger ledger = ledgerWith(Map.of(A, Money.of(10), B, Money.of(5)));

        ApplyResult result = ledger.applyWithResult(transfer("poor", A, B, 11));

        assertThat(result.outcome()).isEqualTo(Outcome.REJECTED_FUNDS);
        assertThat(result.posting()).isEmpty();
        assertThat(ledger.snapshot()).isEqualTo(Map.of(A, Money.of(10), B, Money.of(5)));
        assertThat(rows("SELECT count(*) FROM posting")).containsExactly("0");
        assertThat(rows("SELECT count(*) FROM outbox")).containsExactly("0");
        assertThat(rows("SELECT id || ',' || outcome || ',' || coalesce(posting_seq::text, 'null') FROM transfer"))
                .containsExactly("poor,REJECTED_FUNDS,null");
    }

    @Test
    void REQ_DB_001_invalid_transfers_are_rejected_and_change_nothing() throws SQLException {
        PostgresLedger ledger = ledgerWith(Map.of(A, Money.of(10), B, Money.of(5)));
        AccountId unknown = AccountId.of(99);

        assertThat(ledger.apply(transfer("unknown-target", A, unknown, 1))).isEqualTo(Outcome.REJECTED_INVALID);
        assertThat(ledger.apply(transfer("unknown-source", unknown, B, 1))).isEqualTo(Outcome.REJECTED_INVALID);
        assertThat(ledger.apply(transfer("both-unknown", unknown, AccountId.of(98), 1)))
                .isEqualTo(Outcome.REJECTED_INVALID);
        assertThat(ledger.apply(transfer("same", A, A, 1))).isEqualTo(Outcome.REJECTED_INVALID);
        assertThat(ledger.apply(transfer("zero", A, B, 0))).isEqualTo(Outcome.REJECTED_INVALID);
        assertThat(ledger.apply(transfer("negative", A, B, -5))).isEqualTo(Outcome.REJECTED_INVALID);

        assertThat(ledger.snapshot()).isEqualTo(Map.of(A, Money.of(10), B, Money.of(5)));
        assertThat(rows("SELECT count(*) FROM posting")).containsExactly("0");
        assertThat(rows("SELECT count(*) FROM account")).containsExactly("2");
    }

    @Test
    void REQ_DB_001_balance_of_an_unknown_account_is_empty_and_hasAccount_agrees() {
        PostgresLedger ledger = ledgerWith(Map.of(A, Money.of(10)));

        assertThat(ledger.balance(AccountId.of(99))).isEmpty();
        assertThat(ledger.hasAccount(AccountId.of(99))).isFalse();
        assertThat(ledger.hasAccount(A)).isTrue();
    }

    @Test
    void REQ_DB_010_snapshot_and_balance_agree_and_the_total_equals_the_opening_total() {
        var opening = Fixtures.uniformAccounts(5, 1_000);
        PostgresLedger ledger = ledgerWith(opening);
        for (int i = 0; i < 20; i++) {
            ledger.apply(transfer("t-" + i, AccountId.of(i % 5 + 1), AccountId.of((i + 2) % 5 + 1), 37));
        }

        Map<AccountId, Money> snapshot = ledger.snapshot();

        assertThat(Fixtures.total(snapshot)).isEqualTo(Fixtures.total(opening));
        assertThat(snapshot.keySet().stream().map(AccountId::value).toList())
                .as("ordered by account id")
                .isSorted();
        snapshot.forEach((id, money) -> assertThat(ledger.balance(id)).contains(money));
    }

    @Test
    void REQ_DB_010_snapshot_reads_every_account_in_a_single_statement() {
        LedgerSeed.reset(dataSource(), Fixtures.uniformAccounts(4, 100));
        List<String> sql = new CopyOnWriteArrayList<>();
        PostgresLedger ledger = new PostgresLedger(recording(dataSource(), sql), LockMode.PESSIMISTIC);
        sql.clear();

        ledger.snapshot();

        // Two statements would let a transfer commit between them and tear the total (REQ-LAB-005).
        assertThat(sql.stream().filter(s -> s.toLowerCase().contains("account")).toList())
                .as("statements that read the account table during snapshot()")
                .hasSize(1);
    }

    /** A DataSource whose connections record the text of every statement prepared or executed. */
    private static DataSource recording(DataSource real, List<String> sql) {
        return (DataSource) Proxy.newProxyInstance(
                DataSource.class.getClassLoader(), new Class<?>[] {DataSource.class}, (proxy, method, args) -> {
                    Object result = call(real, method, args);
                    return method.getName().equals("getConnection")
                            ? wrap(Connection.class, (Connection) result, sql, true)
                            : result;
                });
    }

    private static <T> T wrap(Class<T> type, T target, List<String> sql, boolean connection) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, (proxy, method, args) -> {
            if (args != null && args.length > 0 && args[0] instanceof String text) {
                switch (method.getName()) {
                    case "prepareStatement", "prepareCall", "executeQuery", "execute", "executeUpdate", "addBatch" ->
                        sql.add(text);
                    default -> {}
                }
            }
            Object result = call(target, method, args);
            if (connection && result instanceof PreparedStatement ps) {
                return wrap(PreparedStatement.class, ps, sql, false);
            }
            if (connection && result instanceof Statement st) {
                return wrap(Statement.class, st, sql, false);
            }
            return result;
        }));
    }

    private static Object call(Object target, Method method, Object[] args) throws Throwable {
        try {
            return method.invoke(target, args);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }

    private List<String> rows(String query) throws SQLException {
        List<String> out = new ArrayList<>();
        try (Connection c = dataSource().getConnection();
                Statement s = c.createStatement();
                ResultSet rs = s.executeQuery(query)) {
            while (rs.next()) {
                out.add(rs.getString(1));
            }
        }
        return out;
    }
}
