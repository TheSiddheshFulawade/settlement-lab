package com.siddheshfulawade.settlementlab.postgres;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;

/** The optimistic back-off ceiling (REQ-DB-004). Needs no database (not tagged). */
class PostgresLedgerBackoffTest {

    @Test
    void REQ_DB_004_the_ceiling_doubles_from_the_base_and_stops_at_the_cap() {
        assertThat(PostgresLedger.backoffCeiling(1)).isEqualTo(Duration.ofMillis(1));
        assertThat(PostgresLedger.backoffCeiling(2)).isEqualTo(Duration.ofMillis(2));
        assertThat(PostgresLedger.backoffCeiling(6)).isEqualTo(Duration.ofMillis(32));
        assertThat(PostgresLedger.backoffCeiling(7)).isEqualTo(PostgresLedger.BACKOFF_CAP);
        assertThat(PostgresLedger.backoffCeiling(Integer.MAX_VALUE)).isEqualTo(PostgresLedger.BACKOFF_CAP);
    }

    @Test
    void REQ_DB_004a_all_back_offs_of_one_transfer_together_stay_under_a_second() {
        Duration worstCase = Duration.ZERO;
        // No back-off follows the last attempt, so there are MAX - 1 of them.
        for (int failed = 1; failed < PostgresLedger.MAX_OPTIMISTIC_ATTEMPTS; failed++) {
            worstCase = worstCase.plus(PostgresLedger.backoffCeiling(failed));
        }

        assertThat(worstCase).isLessThan(Duration.ofSeconds(1));
    }

    @Test
    void a_ceiling_before_any_conflict_is_a_bug() {
        assertThatThrownBy(() -> PostgresLedger.backoffCeiling(0)).isInstanceOf(IllegalArgumentException.class);
    }
}
