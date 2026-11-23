package com.siddheshfulawade.settlementlab.postgres;

import static org.assertj.core.api.Assertions.assertThat;

import com.siddheshfulawade.settlementlab.gl.FaultProfile;
import com.siddheshfulawade.settlementlab.pipeline.ThreadMode;
import com.siddheshfulawade.settlementlab.runner.RunnerSettings;
import com.siddheshfulawade.settlementlab.runner.ScenarioResult;
import com.siddheshfulawade.settlementlab.runner.ScenarioRunner;
import com.siddheshfulawade.settlementlab.runner.Workload;
import java.time.Duration;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** V3-07: one tiny cell per lock mode through the runner's database path (fast, for CI). */
class DbScenarioRunTest extends PostgresTestBase {

    static Stream<Arguments> cells() {
        return Stream.of(LockMode.values())
                .flatMap(mode -> Stream.of(FaultProfile.NONE, FaultProfile.FLAKY.withLatency(Duration.ZERO))
                        .map(faults -> Arguments.of(mode, faults)));
    }

    @ParameterizedTest
    @MethodSource("cells")
    void REQ_DB_013_a_database_cell_passes_every_invariant_and_drains_the_outbox(LockMode mode, FaultProfile faults) {
        var factory = new PostgresLedgerFactory(dataSource(), mode);

        ScenarioResult row = new ScenarioRunner()
                .run(factory, ThreadMode.VIRTUAL, faults, new Workload(50, 500, 5, 50, 5, 11), RunnerSettings.quick());

        assertThat(row.ledger()).isEqualTo("POSTGRES_" + mode.name());
        assertThat(row.faults()).isEqualTo(faults.name());
        assertThat(row.invariantsPassed()).as(row.firstFailure()).isTrue();
        assertThat(row.duplicates()).isPositive();
        // Every outbox row was delivered and deleted: the GL matches the postings (checked by the invariants).
        assertThat(OutboxTestSupport.count(dataSource(), "outbox")).isZero();
        assertThat(OutboxTestSupport.count(dataSource(), "posting")).isPositive();
    }
}
