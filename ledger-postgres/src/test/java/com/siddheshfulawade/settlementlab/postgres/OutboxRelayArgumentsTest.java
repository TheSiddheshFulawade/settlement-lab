package com.siddheshfulawade.settlementlab.postgres;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.siddheshfulawade.settlementlab.gl.GeneralLedgerPort;
import java.lang.reflect.Proxy;
import java.sql.SQLException;
import java.time.Duration;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

/** Constructor rules of OutboxRelay, and its failure type. Needs no database (not tagged). */
class OutboxRelayArgumentsTest {

    private static final GeneralLedgerPort NEVER_CALLED = posting -> {
        throw new AssertionError("no GL call expected");
    };

    private static final DataSource UNTOUCHED = (DataSource) Proxy.newProxyInstance(
            DataSource.class.getClassLoader(), new Class<?>[] {DataSource.class}, (proxy, method, args) -> {
                throw new AssertionError("the constructor must not use the DataSource: " + method.getName());
            });

    /** A pool that cannot hand out a connection, as when it is exhausted or the database is down. */
    private static final DataSource DOWN = (DataSource) Proxy.newProxyInstance(
            DataSource.class.getClassLoader(), new Class<?>[] {DataSource.class}, (proxy, method, args) -> {
                throw new SQLException("connection refused", "08001");
            });

    @Test
    void the_constructor_touches_neither_the_database_nor_the_gl() {
        assertThatCode(() -> new OutboxRelay(UNTOUCHED, NEVER_CALLED, 1, Duration.ofMillis(1)))
                .doesNotThrowAnyException();
    }

    @Test
    void a_batch_size_under_one_is_refused() {
        assertThatThrownBy(() -> new OutboxRelay(UNTOUCHED, NEVER_CALLED, 0, Duration.ofSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("batchSize");
    }

    @Test
    void a_lease_under_one_millisecond_is_refused_because_it_would_not_hold_a_claim() {
        assertThatThrownBy(() -> new OutboxRelay(UNTOUCHED, NEVER_CALLED, 10, Duration.ofNanos(999_999)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("lease");
        assertThatThrownBy(() -> new OutboxRelay(UNTOUCHED, NEVER_CALLED, 10, Duration.ofMillis(-5)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void nulls_are_refused() {
        assertThatThrownBy(() -> new OutboxRelay(null, NEVER_CALLED, 10, Duration.ofSeconds(1)))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new OutboxRelay(UNTOUCHED, null, 10, Duration.ofSeconds(1)))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new OutboxRelay(UNTOUCHED, NEVER_CALLED, 10, null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void a_database_failure_is_reported_as_a_ledger_database_exception_with_its_sql_state() {
        OutboxRelay relay = new OutboxRelay(DOWN, NEVER_CALLED, 10, Duration.ofSeconds(1));

        assertThatThrownBy(relay::relayOnce)
                .isInstanceOf(LedgerDatabaseException.class)
                .hasMessageContaining("08001")
                .hasCauseInstanceOf(SQLException.class);
        assertThatThrownBy(() -> relay.drain(Duration.ofSeconds(1))).isInstanceOf(LedgerDatabaseException.class);
    }
}
