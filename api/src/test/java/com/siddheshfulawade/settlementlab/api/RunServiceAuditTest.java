package com.siddheshfulawade.settlementlab.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.siddheshfulawade.settlementlab.pipeline.ThreadMode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Edges of the run life cycle found in review: the 60 s bound, an Error in the driver, idle forgetting. */
class RunServiceAuditTest {

    private static final RunRequest QUICK =
            new RunRequest(LedgerChoice.CAS, ThreadMode.VIRTUAL, FaultChoice.NONE, 20, 0);
    private static final RunRequest BIG =
            new RunRequest(LedgerChoice.LOCKING, ThreadMode.VIRTUAL, FaultChoice.FLAKY, 20_000, 0);
    private static final RunRequest OUTAGE =
            new RunRequest(LedgerChoice.LOCKING, ThreadMode.VIRTUAL, FaultChoice.OUTAGE, 300, 0);

    private final RunsProperties relaxed = RunsProperties.defaults()
            .withMaxActive(30)
            .withPerIpPerHour(1_000)
            .withGlobalPerHour(1_000)
            .withTransfersPerSecond(1_000_000);

    private final List<RunService> services = new ArrayList<>();

    @AfterEach
    void closeServices() {
        services.forEach(RunService::close);
    }

    private RunService register(RunService service) {
        services.add(service);
        return service;
    }

    /** What the application builds for a run (the 4-argument constructor needs the factory spelled out). */
    private static RunService.RuntimeFactory defaultRuntimes(RunsProperties properties) {
        return (settings, listener) ->
                SettlementRuntime.start(settings, listener, properties.faultProfile(settings.faults()));
    }

    // ---- L-2: the 60 s bound is on the report, not only on the lookup --------------------------------

    @Test
    @DisplayName("REQ-RUN-005 a report that is ready 60 s or more after the start is not published: the run is ended")
    void lateReportIsNotPublished() {
        MutableClock clock = MutableClock.running(Instant.parse("2026-12-01T10:00:00Z"));
        // an outage of ten minutes: the drain cannot catch up and gives up by itself at 55 s on the clock
        RunsProperties properties = relaxed.withOutage(Duration.ZERO, Duration.ofMinutes(10))
                .withMaxActive(1)
                .withDeadline(Duration.ofHours(1));
        RunService service = register(new RunService(properties, clock));
        Run run = service.start(OUTAGE, "198.51.100.61");
        RecordingSink sink = RecordingSink.normal();
        run.subscribe(sink);
        await().atMost(Duration.ofSeconds(20))
                .until(() -> sink.named("metrics").stream()
                        .anyMatch(event -> ((RunMetrics) event.data()).submitted() == 300));

        clock.advance(Duration.ofSeconds(61)); // the drain gives up and would build its report after 60 s
        run.awaitDriver(TimeUnit.SECONDS.toNanos(20));

        // No service call before this point: nothing but the driver decided what became of the run.
        assertThat(run.report())
                .as("a report made after 60 s is never published")
                .isEmpty();
        assertThat(run.isEnded()).as("the run was ended instead").isTrue();
        assertThat(sink.count("report")).isZero();
        await().atMost(Duration.ofSeconds(5)).until(sink::isClosed);
        assertThat(service.find(run.id())).as("404 for it").isEmpty();
        assertThat(service.start(QUICK, "198.51.100.62")).as("its slot is free").isNotNull();
    }

    // ---- L-5: an Error in the driver ------------------------------------------------------------------

    @Test
    @DisplayName(
            "REQ-RUN-005 an Error (not an Exception) in the driver ends the run, closes its streams, frees the slot")
    void errorInDriverEndsTheRun() {
        // SettlementRuntime is final, so the seam is a Mockito mock whose engine() fails with an Error
        // on the driver's first submit.
        SettlementRuntime broken = mock(SettlementRuntime.class);
        when(broken.engine()).thenThrow(new AssertionError("injected Error in the driver"));
        RunService service =
                register(new RunService(relaxed.withMaxActive(1), Clock.systemUTC(), (settings, listener) -> broken));

        Run run = service.start(QUICK, "198.51.100.71");
        RecordingSink sink = RecordingSink.normal();
        run.subscribe(sink);
        run.awaitDriver(TimeUnit.SECONDS.toNanos(10));

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            assertThat(run.isEnded()).as("ended, not left RUNNING for 60 s").isTrue();
            assertThat(sink.isClosed()).as("its streams are closed").isTrue();
            assertThat(service.find(run.id())).as("404 for it").isEmpty();
        });
        assertThat(run.report()).isEmpty();
        assertThat(service.start(QUICK, "198.51.100.72")).as("its slot is free").isNotNull();
    }

    // ---- L-9: an idle service forgets runs by itself --------------------------------------------------

    @Test
    @DisplayName("REQ-RUN-012 with no further request, a DONE run is forgotten by the timer once its retention passed")
    void idleServiceForgetsFinishedRuns() {
        MutableClock clock = MutableClock.running(Instant.parse("2026-12-01T10:00:00Z"));
        RunService service = register(new RunService(relaxed, clock, defaultRuntimes(relaxed), Duration.ofMillis(20)));
        Run run = service.start(QUICK, "198.51.100.81");
        await().atMost(Duration.ofSeconds(60)).until(() -> run.status() == RunStatus.DONE);
        assertThat(service.storedRuns()).isEqualTo(1);

        clock.advance(RunsProperties.defaults().retention().plusSeconds(1)); // and nobody asks the service anything

        await().atMost(Duration.ofSeconds(10)).until(() -> service.storedRuns() == 0);
    }

    @Test
    @DisplayName("REQ-RUN-005 with no further request, a lost run is ended and forgotten by the timer")
    void idleServiceEndsLostRuns() {
        MutableClock clock = MutableClock.running(Instant.parse("2026-12-01T10:00:00Z"));
        RunsProperties slow = relaxed.withTransfersPerSecond(100).withDeadline(Duration.ofHours(1));
        RunService service = register(new RunService(slow, clock, defaultRuntimes(slow), Duration.ofMillis(20)));
        Run lost = service.start(BIG, "198.51.100.82");
        RecordingSink sink = RecordingSink.normal();
        lost.subscribe(sink);

        clock.advance(Duration.ofSeconds(61)); // 61 s from the 202 with no report; nobody asks the service anything

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            assertThat(lost.isEnded()).as("ended by the timer").isTrue();
            assertThat(sink.isClosed()).as("streams closed").isTrue();
            assertThat(service.storedRuns()).as("forgotten").isZero();
        });
    }
}
