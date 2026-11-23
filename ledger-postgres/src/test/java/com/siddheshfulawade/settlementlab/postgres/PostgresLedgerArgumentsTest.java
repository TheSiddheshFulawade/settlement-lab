package com.siddheshfulawade.settlementlab.postgres;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Proxy;
import java.time.Duration;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

/**
 * Constructor rules of PostgresLedger. Needs no database (not tagged), because the constructor
 * touches nothing: any call on this DataSource fails the test.
 */
class PostgresLedgerArgumentsTest {

    private static final DataSource UNTOUCHED = (DataSource) Proxy.newProxyInstance(
            DataSource.class.getClassLoader(), new Class<?>[] {DataSource.class}, (proxy, method, args) -> {
                throw new AssertionError("the constructor must not use the DataSource: " + method.getName());
            });

    @Test
    void the_constructors_create_and_read_nothing() {
        assertThatCode(() -> new PostgresLedger(UNTOUCHED, LockMode.PESSIMISTIC))
                .doesNotThrowAnyException();
        assertThatCode(() -> new PostgresLedger(
                        UNTOUCHED, LockMode.PESSIMISTIC, Duration.ofMillis(1), Duration.ofMillis(Integer.MAX_VALUE)))
                .doesNotThrowAnyException();
    }

    @Test
    void REQ_DB_009_a_timeout_under_one_millisecond_is_refused_because_zero_means_no_limit() {
        assertThatThrownBy(
                        () -> new PostgresLedger(UNTOUCHED, LockMode.PESSIMISTIC, Duration.ZERO, Duration.ofSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("lockTimeout");
        assertThatThrownBy(() -> new PostgresLedger(
                        UNTOUCHED, LockMode.PESSIMISTIC, Duration.ofSeconds(1), Duration.ofNanos(999_999)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("statementTimeout");
        assertThatThrownBy(() -> new PostgresLedger(
                        UNTOUCHED, LockMode.PESSIMISTIC, Duration.ofMillis(-1), Duration.ofSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void a_timeout_beyond_what_postgresql_accepts_is_refused() {
        Duration tooLong = Duration.ofMillis(Integer.MAX_VALUE + 1L);

        assertThatThrownBy(() -> new PostgresLedger(UNTOUCHED, LockMode.PESSIMISTIC, tooLong, Duration.ofSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void nulls_are_refused() {
        assertThatThrownBy(() -> new PostgresLedger(null, LockMode.PESSIMISTIC))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new PostgresLedger(UNTOUCHED, null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new PostgresLedger(UNTOUCHED, LockMode.PESSIMISTIC, null, Duration.ofSeconds(1)))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void REQ_DB_004_optimistic_mode_is_accepted_and_the_constructor_still_touches_nothing() {
        assertThatCode(() -> new PostgresLedger(UNTOUCHED, LockMode.OPTIMISTIC)).doesNotThrowAnyException();
        assertThatCode(() -> new PostgresLedger(
                        UNTOUCHED, LockMode.OPTIMISTIC, Duration.ofMillis(1), Duration.ofMillis(Integer.MAX_VALUE)))
                .doesNotThrowAnyException();
    }
}
