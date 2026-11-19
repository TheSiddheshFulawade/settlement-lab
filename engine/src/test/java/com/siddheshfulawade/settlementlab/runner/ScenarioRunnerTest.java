package com.siddheshfulawade.settlementlab.runner;

import static org.assertj.core.api.Assertions.assertThat;

import com.siddheshfulawade.settlementlab.gl.FaultProfile;
import com.siddheshfulawade.settlementlab.pipeline.ThreadMode;
import java.time.Duration;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class ScenarioRunnerTest {

    @ParameterizedTest
    @EnumSource(LedgerType.class)
    @org.junit.jupiter.api.DisplayName("REQ-LAB-015 a small scenario runs end to end and every invariant passes")
    void smallScenarioPasses(LedgerType ledger) {
        var scenario = new Scenario(
                ledger,
                ThreadMode.VIRTUAL,
                FaultProfile.FLAKY.withLatency(Duration.ZERO),
                new Workload(50, 2_000, 5, 50, 5, 7),
                RunnerSettings.quick());

        ScenarioResult result = new ScenarioRunner().run(scenario);

        assertThat(result.invariantsPassed()).as(result.firstFailure()).isTrue();
        assertThat(result.transfers()).isEqualTo(2_000);
        assertThat(result.duplicates()).isPositive();
        assertThat(result.throughputPerSecond()).isPositive();
        assertThat(result.p99Micros()).isGreaterThanOrEqualTo(result.p50Micros());
    }
}
