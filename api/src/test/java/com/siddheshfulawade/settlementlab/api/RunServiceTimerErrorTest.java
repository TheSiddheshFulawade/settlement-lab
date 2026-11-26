package com.siddheshfulawade.settlementlab.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.siddheshfulawade.settlementlab.pipeline.ThreadMode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * N-1: a scheduled task that throws is never run again, and that includes an {@code Error}. The service's
 * two timer tasks (forget old visitors, sample a run) must survive one, or an idle service stops
 * forgetting visitors (memory) and a live run stops sending metrics, silently, for good.
 */
class RunServiceTimerErrorTest {

    private static final RunRequest QUICK =
            new RunRequest(LedgerChoice.CAS, ThreadMode.VIRTUAL, FaultChoice.NONE, 20, 0);
    private static final RunRequest BIG =
            new RunRequest(LedgerChoice.LOCKING, ThreadMode.VIRTUAL, FaultChoice.NONE, 20_000, 0);

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

    /** Reads like the wrapped clock, but throws one Error the first time the timer thread asks for the time. */
    private static final class ErrorOnceOnTimerThread extends Clock {
        private final Clock delegate;
        private final AtomicBoolean armed = new AtomicBoolean();

        ErrorOnceOnTimerThread(Clock delegate) {
            this.delegate = delegate;
        }

        void arm() {
            armed.set(true);
        }

        boolean thrown() {
            return !armed.get();
        }

        @Override
        public ZoneId getZone() {
            return delegate.getZone();
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            if (armed.get()
                    && "run-sampler".equals(Thread.currentThread().getName())
                    && armed.compareAndSet(true, false)) {
                throw new AssertionError("injected Error on the timer thread");
            }
            return delegate.instant();
        }
    }

    @Test
    @DisplayName(
            "REQ-RUN-006 an Error in one forgetting tick does not stop the timer: old visitors are still forgotten")
    void errorInForgetTickDoesNotCancelTheTimer() {
        MutableClock base = MutableClock.running(Instant.parse("2026-12-01T10:00:00Z"));
        ErrorOnceOnTimerThread clock = new ErrorOnceOnTimerThread(base);
        RunsProperties properties = relaxed.withSampleInterval(Duration.ofHours(1)); // no sampling ticks
        RunService service = new RunService(
                properties,
                clock,
                (settings, listener) ->
                        SettlementRuntime.start(settings, listener, properties.faultProfile(settings.faults())),
                Duration.ofMillis(20));
        services.add(service);
        Run run = service.start(QUICK, "198.51.100.101");
        await().atMost(Duration.ofSeconds(60)).until(() -> run.status() == RunStatus.DONE);
        assertThat(service.trackedVisitors()).isEqualTo(1);

        clock.arm();
        base.advance(Duration.ofHours(2)); // the visitor's start is now older than the window
        await().atMost(Duration.ofSeconds(10)).until(clock::thrown);

        await().atMost(Duration.ofSeconds(5))
                .untilAsserted(() -> assertThat(service.trackedVisitors())
                        .as("the timer survived the Error and forgot the visitor")
                        .isZero());
    }

    @Test
    @DisplayName("REQ-RUN-005 an Error in one sampling tick does not stop the run's later metrics events")
    void errorInSamplingTickDoesNotSilenceTheRun() {
        MutableClock base = MutableClock.running(Instant.parse("2026-12-01T10:00:00Z"));
        ErrorOnceOnTimerThread clock = new ErrorOnceOnTimerThread(base);
        RunsProperties properties = relaxed.withTransfersPerSecond(100) // 20,000 transfers at 100/s: stays running
                .withDeadline(Duration.ofHours(1))
                .withSampleInterval(Duration.ofMillis(20));
        RunService service = new RunService(
                properties,
                clock,
                (settings, listener) ->
                        SettlementRuntime.start(settings, listener, properties.faultProfile(settings.faults())),
                Duration.ofHours(1)); // no forgetting ticks
        services.add(service);
        Run run = service.start(BIG, "198.51.100.102");
        RecordingSink sink = RecordingSink.normal();
        run.subscribe(sink);
        await().atMost(Duration.ofSeconds(10)).until(() -> sink.count("metrics") >= 2);

        clock.arm();
        await().atMost(Duration.ofSeconds(10)).until(clock::thrown);
        long afterError = sink.count("metrics");

        await().atMost(Duration.ofSeconds(5))
                .untilAsserted(() -> assertThat(sink.count("metrics"))
                        .as("metrics events keep coming after the Error")
                        .isGreaterThanOrEqualTo(afterError + 3));
    }
}
