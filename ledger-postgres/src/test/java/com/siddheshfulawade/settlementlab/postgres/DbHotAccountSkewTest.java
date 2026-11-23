package com.siddheshfulawade.settlementlab.postgres;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Study task for the user (V3-07). */
@Tag("database")
class DbHotAccountSkewTest extends PostgresTestBase {

    @Test
    @Disabled("TODO-ME: write it, then remove this line")
    void REQ_DB_013_optimistic_conflicts_grow_with_the_hot_percent() {
        // TODO-ME: run an OPTIMISTIC cell through ScenarioRunner (see DbScenarioRunTest) at hotPercent 0
        // and at hotPercent 90 on the same seed, read PostgresLedger.conflicts() from each ledger
        // (keep a reference from PostgresLedgerFactory.create), and assert conflicts at 90 > at 0.
        // Check: it passes; then make hotPercent 0 and 90 equal and see it fail.
    }
}
