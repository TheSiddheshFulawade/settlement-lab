package com.siddheshfulawade.settlementlab.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.siddheshfulawade.settlementlab.pipeline.ThreadMode;
import java.lang.ref.WeakReference;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * REQ-RUN-012: a finished or ended run kept in the registry (or still held by a caller) keeps only its
 * report, never the engine stack it was made with (engine, postings, ledger, general ledger, outbox).
 */
class RunRegistryMemoryTest {

    private static final String IP = "198.51.100.40";

    private static final RunRequest QUICK =
            new RunRequest(LedgerChoice.CAS, ThreadMode.VIRTUAL, FaultChoice.NONE, 50, 0);
    private static final RunRequest BIG =
            new RunRequest(LedgerChoice.LOCKING, ThreadMode.VIRTUAL, FaultChoice.FLAKY, 20_000, 0);

    private final RunsProperties relaxed = RunsProperties.defaults()
            .withMaxActive(30)
            .withPerIpPerHour(1_000)
            .withGlobalPerHour(1_000)
            .withTransfersPerSecond(1_000_000);

    private final AtomicReference<WeakReference<SettlementRuntime>> started = new AtomicReference<>();
    private RunService service;

    @AfterEach
    void close() {
        if (service != null) {
            service.close();
        }
    }

    private RunService serviceWith(RunsProperties properties, MutableClock clock) {
        service = new RunService(properties, clock, (settings, listener) -> {
            SettlementRuntime runtime = SettlementRuntime.start(settings, listener);
            started.set(new WeakReference<>(runtime)); // weak: this test must not be what keeps it alive
            return runtime;
        });
        return service;
    }

    private void assertRuntimeIsCollected() {
        WeakReference<SettlementRuntime> runtime = started.get();
        assertThat(runtime).as("the factory was called").isNotNull();
        await().atMost(Duration.ofSeconds(20))
                .pollInterval(Duration.ofMillis(100))
                .until(() -> {
                    System.gc();
                    return runtime.get() == null;
                });
    }

    @Test
    @DisplayName("REQ-RUN-012 a DONE run in the registry no longer reaches its engine stack")
    void doneRunReleasesItsRuntime() {
        MutableClock clock = MutableClock.running(Instant.parse("2026-12-01T10:00:00Z"));
        RunService registry = serviceWith(relaxed, clock);
        Run run = registry.start(QUICK, IP);
        await().atMost(Duration.ofSeconds(60)).until(() -> run.status() == RunStatus.DONE);
        run.awaitDriver(TimeUnit.SECONDS.toNanos(10)); // the driver has closed everything and is finished

        assertRuntimeIsCollected();

        assertThat(registry.find(run.id())).as("still in the registry").containsSame(run);
        assertThat(run.report()).as("with its report").isPresent();
        assertThat(run.report().orElseThrow().allPassed()).isTrue();
    }

    @Test
    @DisplayName("REQ-RUN-012 an ended (expired) run that a caller still holds no longer reaches its engine stack")
    void endedRunReleasesItsRuntime() {
        MutableClock clock = MutableClock.running(Instant.parse("2026-12-01T10:00:00Z"));
        RunService registry = serviceWith(relaxed.withTransfersPerSecond(100).withDeadline(Duration.ofHours(1)), clock);
        Run lost = registry.start(BIG, IP);

        clock.advance(Duration.ofSeconds(61)); // a run without a report for over 60 s is lost
        assertThat(registry.find(lost.id())).as("expired by this request").isEmpty();
        lost.awaitDriver(TimeUnit.SECONDS.toNanos(10)); // its driver saw the stop request and closed the engine

        assertRuntimeIsCollected();

        assertThat(lost.isEnded()).isTrue(); // `lost` is used here, so the test holds the Run to the end
        assertThat(lost.report()).isEmpty();
    }
}
