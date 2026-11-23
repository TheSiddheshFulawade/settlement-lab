package com.siddheshfulawade.settlementlab.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import com.siddheshfulawade.settlementlab.pipeline.ThreadMode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The run lifecycle, events, pacing, deadlines, expiry, retention and report with no HTTP.
 *
 * <p>ASSUMED API, all package-private in {@code com.siddheshfulawade.settlementlab.api}:
 *
 * <ul>
 *   <li>{@code record RunRequest(LedgerChoice ledger, ThreadMode threads, FaultChoice faults, int transfers,
 *       int duplicatePercent)}
 *   <li>{@code record RunsProperties(String previewOrigin, Duration deadline, Duration retention, Duration
 *       sampleInterval, int perIpPerHour, int globalPerHour, int maxActive, int subscribersPerRun, int
 *       eventBuffer, int transfersPerSecond, List<String> trustedProxyRanges, Duration outageStart,
 *       Duration outageLength)} bound to {@code settlement.runs} (kebab-case). {@code static defaults()}:
 *       preview origin empty, 20 s, 10 min, 250 ms, 5, 60, 2, 2, any buffer, 2000, empty list, 1 s, 3 s.
 *       Copies: {@code withDeadline, withRetention, withSampleInterval, withPerIpPerHour, withGlobalPerHour,
 *       withMaxActive, withSubscribersPerRun, withEventBuffer, withTransfersPerSecond,
 *       withTrustedProxyRanges(List), withPreviewOrigin(String), withOutage(Duration start, Duration length)}
 *   <li>{@code RunService(RunsProperties, Clock)} and {@code RunService(RunsProperties, Clock,
 *       RunService.RuntimeFactory)} where {@code RuntimeFactory} is {@code SettlementRuntime
 *       start(SettlementProperties, SettlementListener)}; {@code Run start(RunRequest, String clientKey)}
 *       throwing {@code RunRefusedException} ({@code limit()}, {@code retryAfterSeconds()}); {@code
 *       Optional<Run> find(String runId)}; {@code int storedRuns()}; {@code close()}. Every start, find and
 *       the HTTP requests built on them first expire runs past 60 s without a report. After close, start
 *       throws IllegalStateException.
 *   <li>{@code Run}: {@code id()}, {@code status()} ({@code RunStatus.RUNNING|DONE}), {@code
 *       Optional<RunReport> report()}, {@code Runnable subscribe(RunSink)} (a third subscriber of an
 *       unfinished run throws {@code SubscriberLimitException}; the Runnable cancels)
 *   <li>{@code interface RunSink {void send(RunEvent) throws Exception; void close();}}, {@code record
 *       RunEvent(String name, Object data)} (no id), name {@code "metrics"} (data: {@code RunMetrics} with
 *       the contract's fields as accessors) or {@code "report"} (data: {@code RunReport})
 *   <li>{@code record RunReport(...)} with the contract's fields as accessors, {@code
 *       List<RunReport.InvariantLine> invariants()}, {@code InvariantLine(String name, boolean passed,
 *       String counterexample)}
 * </ul>
 */
class RunServiceTest {

    private static final String IP = "198.51.100.7";
    private static final long OPENING_TOTAL = 1_000 * 10_000L;

    private static final RunRequest BIG =
            new RunRequest(LedgerChoice.LOCKING, ThreadMode.VIRTUAL, FaultChoice.FLAKY, 20_000, 0);
    private static final RunRequest QUICK =
            new RunRequest(LedgerChoice.CAS, ThreadMode.VIRTUAL, FaultChoice.NONE, 10, 0);

    /** Fast: the driver is not the bottleneck. */
    private final RunsProperties relaxed = RunsProperties.defaults()
            .withMaxActive(30)
            .withPerIpPerHour(1_000)
            .withGlobalPerHour(1_000)
            .withTransfersPerSecond(1_000_000)
            .withOutage(Duration.ofMillis(100), Duration.ofMillis(400));

    /** Slow: 20,000 transfers at 100/s would take 200 s, so a run stays active until something ends it. */
    private final RunsProperties held = relaxed.withTransfersPerSecond(100);

    private final List<RunService> services = new ArrayList<>();

    private RunService service(RunsProperties properties) {
        return service(properties, Clock.systemUTC());
    }

    private RunService service(RunsProperties properties, Clock clock) {
        RunService service = new RunService(properties, clock);
        services.add(service);
        return service;
    }

    @AfterEach
    void closeServices() {
        services.forEach(RunService::close);
    }

    private static RunReport finished(Run run) {
        await().atMost(Duration.ofSeconds(60)).until(() -> run.status() == RunStatus.DONE);
        return run.report().orElseThrow();
    }

    private static long engineThreads() {
        return Thread.getAllStackTraces().keySet().stream()
                .filter(Thread::isAlive)
                .map(Thread::getName)
                .filter(name -> name.equals("gl-relay") || name.startsWith("settle-"))
                .count();
    }

    private static long runOwnedThreads() {
        return Thread.getAllStackTraces().keySet().stream()
                .filter(Thread::isAlive)
                .map(Thread::getName)
                .filter(name -> name.equals("gl-relay") || name.startsWith("settle-") || name.startsWith("run-"))
                .count();
    }

    private static void assertAllPassed(RunReport report) {
        assertThat(report.invariants())
                .as("invariants of %s", report.settings())
                .hasSize(6)
                .allSatisfy(line -> assertThat(line.passed())
                        .as(line.name() + ": " + line.counterexample())
                        .isTrue());
    }

    // ---- defaults the contract promises -------------------------------------------------------------

    @Test
    @DisplayName("REQ-RUN-005 to 012 the default limits are the contract's numbers")
    void defaultsMatchTheContract() {
        RunsProperties defaults = RunsProperties.defaults();

        assertThat(defaults.deadline()).isEqualTo(Duration.ofSeconds(20));
        assertThat(defaults.retention()).isEqualTo(Duration.ofMinutes(10));
        assertThat(defaults.sampleInterval()).isEqualTo(Duration.ofMillis(250));
        assertThat(defaults.perIpPerHour()).isEqualTo(5);
        assertThat(defaults.globalPerHour()).isEqualTo(60);
        assertThat(defaults.maxActive()).isEqualTo(2);
        assertThat(defaults.subscribersPerRun()).isEqualTo(2);
        assertThat(defaults.transfersPerSecond()).isEqualTo(2_000);
        assertThat(defaults.trustedProxyRanges()).isEmpty();
        assertThat(defaults.outageStart()).isEqualTo(Duration.ofSeconds(1));
        assertThat(defaults.outageLength()).isEqualTo(Duration.ofSeconds(3));
    }

    // ---- report -------------------------------------------------------------------------------------

    @Test
    @DisplayName("REQ-RUN-001 a started run is RUNNING and can be found by its id")
    void startedRunIsRunningAndFindable() {
        RunService service = service(held);

        Run run = service.start(BIG, IP);

        assertThat(run.id()).matches("[A-Za-z0-9-]{1,64}");
        assertThat(run.status()).isEqualTo(RunStatus.RUNNING);
        assertThat(run.report()).isEmpty();
        assertThat(service.find(run.id())).containsSame(run);
    }

    @Test
    @DisplayName("REQ-RUN-010 the report has every contract field, six invariants in order, and all pass")
    void reportHasEveryField() {
        RunService service = service(relaxed);
        RunRequest request = new RunRequest(LedgerChoice.CAS, ThreadMode.PLATFORM, FaultChoice.NONE, 500, 5);

        RunReport report = finished(service.start(request, IP));

        assertThat(report.status()).isEqualTo(RunStatus.DONE);
        assertThat(report.stoppedEarly()).isFalse();
        assertThat(report.settings()).isEqualTo(request);
        assertThat(report.transfersCompleted()).isEqualTo(500);
        assertThat(report.openingTotal()).isEqualTo(OPENING_TOTAL);
        assertThat(report.closingTotal()).isEqualTo(OPENING_TOTAL);
        assertThat(report.durationMs()).isNotNegative();
        assertThat(report.throughputPerSecond()).isPositive();
        assertThat(report.throughputPerSecond())
                .as("postings divided by duration cannot beat completed transfers divided by duration")
                .isLessThanOrEqualTo(500 * 1_000L / Math.max(report.durationMs(), 1) + 1);
        assertThat(report.p50Micros()).isNotNegative();
        assertThat(report.p99Micros()).isGreaterThanOrEqualTo(report.p50Micros());
        assertThat(report.glRetries()).isZero();
        assertThat(report.breakerOpens()).isZero();
        assertThat(report.invariants())
                .extracting(RunReport.InvariantLine::name)
                .containsExactlyElementsOf(RunHttp.INVARIANT_NAMES);
        assertThat(report.invariants())
                .allSatisfy(line -> assertThat(line.counterexample()).isNull());
        assertThat(report.allPassed()).isTrue();
    }

    @Test
    @DisplayName("REQ-RUN-010 every ledger, thread mode and fault choice passes all six invariants")
    void everyCombinationPassesAllInvariants() {
        RunService service = service(relaxed);
        List<Run> runs = new ArrayList<>();
        for (LedgerChoice ledger : LedgerChoice.values()) {
            for (ThreadMode threads : ThreadMode.values()) {
                for (FaultChoice faults : List.of(FaultChoice.NONE, FaultChoice.FLAKY, FaultChoice.OUTAGE)) {
                    runs.add(service.start(new RunRequest(ledger, threads, faults, 300, 5), IP));
                }
            }
        }

        for (Run run : runs) {
            RunReport report = finished(run);
            assertThat(report.invariants())
                    .extracting(RunReport.InvariantLine::name)
                    .containsExactlyElementsOf(RunHttp.INVARIANT_NAMES);
            assertAllPassed(report);
            assertThat(report.closingTotal()).as("%s", report.settings()).isEqualTo(report.openingTotal());
            assertThat(report.durationMs())
                    .as("within the 60 s bound: %s", report.settings())
                    .isLessThan(60_000);
        }
    }

    @Test
    @DisplayName("REQ-RUN-010 duplicates are sent but never applied")
    void duplicatesAreSentButNotApplied() {
        RunService service = service(relaxed);

        RunReport withDuplicates = finished(service.start(
                new RunRequest(LedgerChoice.LOCKING, ThreadMode.VIRTUAL, FaultChoice.NONE, 1_000, 10), IP));
        RunReport without = finished(service.start(
                new RunRequest(LedgerChoice.LOCKING, ThreadMode.VIRTUAL, FaultChoice.NONE, 1_000, 0), IP));

        assertThat(withDuplicates.duplicatesSent()).isPositive().isLessThan(1_000);
        assertThat(withDuplicates.duplicatesApplied()).isZero();
        assertAllPassed(withDuplicates);
        assertThat(without.duplicatesSent()).isZero();
    }

    @Test
    @DisplayName("REQ-RUN-009 the same request twice gives the same workload (fixed seed per run)")
    void fixedSeedGivesSameWorkload() {
        RunService service = service(relaxed);
        RunRequest request = new RunRequest(LedgerChoice.CAS, ThreadMode.VIRTUAL, FaultChoice.NONE, 800, 7);

        RunReport first = finished(service.start(request, IP));
        RunReport second = finished(service.start(request, IP));

        assertThat(second.duplicatesSent()).isEqualTo(first.duplicatesSent()).isPositive();
        assertThat(second.transfersCompleted()).isEqualTo(first.transfersCompleted());
    }

    @Test
    @DisplayName("REQ-RUN-009 two concurrent runs share no state")
    void concurrentRunsShareNothing() {
        RunService service = service(relaxed);
        Run a = service.start(new RunRequest(LedgerChoice.LOCKING, ThreadMode.VIRTUAL, FaultChoice.NONE, 500, 0), IP);
        Run b = service.start(new RunRequest(LedgerChoice.CAS, ThreadMode.PLATFORM, FaultChoice.NONE, 700, 10), IP);

        RunReport reportA = finished(a);
        RunReport reportB = finished(b);

        assertThat(reportA.transfersCompleted()).isEqualTo(500);
        assertThat(reportA.duplicatesSent()).isZero();
        assertThat(reportB.transfersCompleted()).isEqualTo(700);
        assertThat(reportB.duplicatesSent()).isPositive();
        assertAllPassed(reportA);
        assertAllPassed(reportB);
        assertThat(reportA.runId()).isNotEqualTo(reportB.runId());
        assertThat(reportA.throughputPerSecond()).isPositive();
        assertThat(reportB.throughputPerSecond()).isPositive();
    }

    @Test
    @DisplayName("REQ-RUN-009 when the report is out, the run's engine and outbox threads are gone")
    void finishedRunsLeaveNoThreads() {
        long before = engineThreads();
        RunService service = service(relaxed);
        List<Run> runs = new ArrayList<>();
        for (ThreadMode threads : ThreadMode.values()) {
            runs.add(service.start(new RunRequest(LedgerChoice.LOCKING, threads, FaultChoice.FLAKY, 1_000, 2), IP));
        }
        runs.forEach(RunServiceTest::finished);

        await().atMost(Duration.ofSeconds(10))
                .untilAsserted(() -> assertThat(engineThreads())
                        .as("threads left by finished runs")
                        .isLessThanOrEqualTo(before));
    }

    @Test
    @DisplayName("REQ-RUN-009 a run stopped by the deadline also closes everything it opened")
    void stoppedRunsLeaveNoThreads() {
        long before = engineThreads();
        RunService service = service(held.withDeadline(Duration.ofMillis(1)));

        finished(service.start(BIG, IP));

        await().atMost(Duration.ofSeconds(10))
                .untilAsserted(() -> assertThat(engineThreads())
                        .as("threads left by a stopped run")
                        .isLessThanOrEqualTo(before));
    }

    // ---- pacing and duration ------------------------------------------------------------------------

    @Test
    @DisplayName(
            "REQ-RUN-009 the driver submits at the configured steady rate, so the paced rate is reported, not capacity")
    void driverIsPaced() {
        RunService service = service(relaxed.withTransfersPerSecond(400));

        RunReport report = finished(
                service.start(new RunRequest(LedgerChoice.CAS, ThreadMode.VIRTUAL, FaultChoice.NONE, 400, 0), IP));

        // 400 transfers at 400/s promise a submit phase of about 1 s; lower bound with slack for a late first tick
        assertThat(report.durationMs()).isGreaterThanOrEqualTo(800).isLessThan(20_000);
        assertThat(report.throughputPerSecond()).isPositive().isLessThanOrEqualTo(480);
        assertThat(report.transfersCompleted()).isEqualTo(400);
    }

    @Test
    @DisplayName("REQ-RUN-003 metrics keep coming during the drain, and durationMs includes the drain")
    void metricsContinueDuringDrain() {
        // everything is submitted within milliseconds; the outage (0 to 1.5 s) then holds the drain open
        RunService service = service(relaxed.withOutage(Duration.ZERO, Duration.ofMillis(1_500)));
        Run run =
                service.start(new RunRequest(LedgerChoice.LOCKING, ThreadMode.VIRTUAL, FaultChoice.OUTAGE, 300, 0), IP);
        RecordingSink sink = RecordingSink.normal();
        run.subscribe(sink);

        RunReport report = finished(run);
        await().atMost(Duration.ofSeconds(5)).until(sink::isClosed);

        long metricsAfterSubmitting = sink.named("metrics").stream()
                .map(event -> (RunMetrics) event.data())
                .filter(metrics -> metrics.submitted() == 300)
                .count();
        assertThat(metricsAfterSubmitting).as("metrics events during the drain").isGreaterThanOrEqualTo(2);
        assertThat(report.durationMs()).as("drain included").isGreaterThanOrEqualTo(1_400);
        assertThat(report.glRetries() + report.breakerOpens())
                .as("the outage was seen")
                .isPositive();
        assertAllPassed(report);
    }

    // ---- deadline, 60 s bound, expiry (wall time through the injected clock) ------------------------

    @Test
    @DisplayName(
            "REQ-RUN-005 a run past its deadline on the injected clock is stopped early, drained, and every invariant passes")
    void deadlineStopsTheRunButItStillDrainsAndChecks() {
        MutableClock clock = MutableClock.running(Instant.parse("2026-12-01T10:00:00Z"));
        RunService service = service(held, clock);
        Run run = service.start(new RunRequest(LedgerChoice.CAS, ThreadMode.VIRTUAL, FaultChoice.FLAKY, 20_000, 5), IP);
        RecordingSink sink = RecordingSink.normal();
        run.subscribe(sink);
        assertThat(run.status()).isEqualTo(RunStatus.RUNNING);

        clock.advance(Duration.ofSeconds(21)); // the 20 s of wall time are over; no real waiting

        RunReport report = finished(run);
        assertThat(report.stoppedEarly()).isTrue();
        assertThat(report.transfersCompleted()).isLessThan(20_000);
        assertThat(report.durationMs()).isGreaterThanOrEqualTo(20_000);
        assertAllPassed(report);
        assertThat(report.closingTotal()).isEqualTo(report.openingTotal());
        await().atMost(Duration.ofSeconds(10)).until(sink::isClosed);
        assertThat(sink.count("report")).isEqualTo(1);
    }

    @Test
    @DisplayName("REQ-RUN-005 a run that has not reached its deadline is not stopped")
    void beforeTheDeadlineNothingStops() {
        MutableClock clock = MutableClock.running(Instant.parse("2026-12-01T10:00:00Z"));
        RunService service = service(held, clock);
        Run run = service.start(BIG, IP);

        clock.advance(Duration.ofSeconds(19));
        // a couple of sampler ticks pass in real time
        await().pollDelay(Duration.ofMillis(700)).atMost(Duration.ofSeconds(5)).until(() -> true);

        assertThat(run.status()).isEqualTo(RunStatus.RUNNING);
    }

    @Test
    @DisplayName("REQ-RUN-005 an OUTAGE run stopped by the deadline still drains and every invariant passes")
    void outageRunStoppedByDeadlineStillDrains() {
        // the outage (0 to 1.5 s from the start) is still on when the deadline (300 ms) stops the submissions
        RunService service =
                service(held.withDeadline(Duration.ofMillis(300)).withOutage(Duration.ZERO, Duration.ofMillis(1_500)));

        RunReport report = finished(service.start(
                new RunRequest(LedgerChoice.LOCKING, ThreadMode.VIRTUAL, FaultChoice.OUTAGE, 20_000, 0), IP));

        assertThat(report.stoppedEarly()).isTrue();
        assertAllPassed(report);
        assertThat(report.durationMs()).isGreaterThanOrEqualTo(1_400);
    }

    @Test
    @DisplayName(
            "REQ-RUN-005 the drain is bounded: a general ledger that never recovers gives a report with GL_MATCHES_POSTINGS failed")
    void unrecoveredGeneralLedgerGivesAnHonestFailedReport() {
        MutableClock clock = MutableClock.running(Instant.parse("2026-12-01T10:00:00Z"));
        // an outage of ten minutes from the start: the drain can never catch up
        RunService service = service(relaxed.withOutage(Duration.ZERO, Duration.ofMinutes(10)), clock);
        Run run =
                service.start(new RunRequest(LedgerChoice.LOCKING, ThreadMode.VIRTUAL, FaultChoice.OUTAGE, 300, 0), IP);
        RecordingSink sink = RecordingSink.normal();
        run.subscribe(sink);
        await().atMost(Duration.ofSeconds(20))
                .until(() -> sink.named("metrics").stream()
                        .anyMatch(event -> ((RunMetrics) event.data()).submitted() == 300));

        clock.advance(Duration.ofSeconds(59)); // still inside the 60 s bound; the drain has to give up by then

        RunReport report = finished(run);
        assertThat(report.invariants()).hasSize(6);
        assertThat(report.invariants())
                .filteredOn(line -> !line.name().equals("GL_MATCHES_POSTINGS"))
                .allSatisfy(line -> assertThat(line.passed()).as(line.name()).isTrue());
        RunReport.InvariantLine gl = report.invariants().get(5);
        assertThat(gl.name()).isEqualTo("GL_MATCHES_POSTINGS");
        assertThat(gl.passed()).isFalse();
        assertThat(gl.counterexample()).isNotBlank().hasSizeLessThanOrEqualTo(500);
        assertThat(report.allPassed()).isFalse();
        assertThat(service.find(run.id())).as("reported, not expired").isPresent();
    }

    @Test
    @DisplayName("REQ-RUN-005 a run past 60 s without a report is expired by the next request: closed, slot freed, 404")
    void lostRunIsExpiredByTheNextRequest() {
        long before = engineThreads();
        MutableClock clock = MutableClock.running(Instant.parse("2026-12-01T10:00:00Z"));
        // a deadline longer than 60 s stands for a run whose own deadline never fired (the CPU was away)
        RunService service = service(held.withMaxActive(1).withDeadline(Duration.ofHours(1)), clock);
        Run run = service.start(BIG, IP);
        RecordingSink sink = RecordingSink.normal();
        run.subscribe(sink);

        clock.advance(Duration.ofSeconds(59));
        assertThat(service.find(run.id())).as("59 s: still running").isPresent();
        assertThat(sink.isClosed()).isFalse();

        clock.advance(Duration.ofSeconds(2)); // 61 s from the 202, no report
        assertThat(service.find(run.id())).as("expired on this request").isEmpty();
        assertThat(sink.isClosed()).as("its streams are closed").isTrue();
        assertThat(sink.count("report")).isZero();
        assertThat(service.start(QUICK, "198.51.100.99")).as("its slot is free").isNotNull();
        await().atMost(Duration.ofSeconds(10))
                .untilAsserted(() -> assertThat(engineThreads())
                        .as("its engine is closed")
                        .isLessThanOrEqualTo(before + 1)); // the new quick run may still hold one set
    }

    @Test
    @DisplayName("REQ-RUN-005 any runs request expires a lost run: starting another one frees the only slot")
    void startingARunExpiresTheLostOne() {
        MutableClock clock = MutableClock.running(Instant.parse("2026-12-01T10:00:00Z"));
        RunService service = service(held.withMaxActive(1).withDeadline(Duration.ofHours(1)), clock);
        Run lost = service.start(BIG, IP);

        clock.advance(Duration.ofSeconds(61));

        assertThat(service.start(QUICK, "198.51.100.50")).isNotNull();
        assertThat(service.find(lost.id())).isEmpty();
        assertThat(service.storedRuns()).isLessThanOrEqualTo(1);
    }

    @Test
    @DisplayName("REQ-RUN-005 a run at 59.5 s is not expired; at 60.5 s it is")
    void expiryBoundary() {
        MutableClock clock = MutableClock.running(Instant.parse("2026-12-01T10:00:00Z"));
        RunService service = service(held.withDeadline(Duration.ofHours(1)), clock);
        Run run = service.start(BIG, IP);

        clock.advance(Duration.ofSeconds(59).plusMillis(500));
        assertThat(service.find(run.id())).isPresent();

        clock.advance(Duration.ofSeconds(1));
        assertThat(service.find(run.id())).isEmpty();
    }

    // ---- events -------------------------------------------------------------------------------------

    @Test
    @DisplayName("REQ-RUN-003 events: metrics about every 250 ms, exactly one report last, then the stream closes")
    void eventsAreMetricsThenOneReportThenClose() {
        RunService service = service(relaxed.withTransfersPerSecond(200));
        Run run = service.start(new RunRequest(LedgerChoice.LOCKING, ThreadMode.VIRTUAL, FaultChoice.NONE, 400, 0), IP);
        RecordingSink sink = RecordingSink.normal();
        run.subscribe(sink);

        await().atMost(Duration.ofSeconds(60)).until(sink::isClosed);

        List<RunEvent> events = sink.events;
        assertThat(sink.count("report")).isEqualTo(1);
        assertThat(events.get(events.size() - 1).name())
                .as("the report is last")
                .isEqualTo("report");
        assertThat(events).extracting(RunEvent::name).containsOnly("metrics", "report");
        long durationMs = run.report().orElseThrow().durationMs();
        long expected = durationMs / 250;
        assertThat((long) sink.named("metrics").size())
                .as("metrics events for a run of %d ms", durationMs)
                .isBetween(Math.max(0, expected - 3), expected + 3);
        assertThat(sink.closeCalls.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("REQ-RUN-004 connecting after the run finished gets the report at once and the stream closes")
    void lateSubscriberGetsReportOnly() {
        RunService service = service(relaxed);
        Run run = service.start(new RunRequest(LedgerChoice.CAS, ThreadMode.VIRTUAL, FaultChoice.NONE, 200, 0), IP);
        finished(run);
        RecordingSink sink = RecordingSink.normal();

        run.subscribe(sink);

        await().atMost(Duration.ofSeconds(2)).until(sink::isClosed);
        assertThat(sink.events).extracting(RunEvent::name).containsExactly("report");
    }

    @Test
    @DisplayName("REQ-RUN-011 a third subscriber is refused; a cancelled one frees its place")
    void atMostTwoSubscribers() {
        RunService service = service(held);
        Run run = service.start(BIG, IP);
        Runnable cancelFirst = run.subscribe(RecordingSink.normal());
        run.subscribe(RecordingSink.normal());

        assertThatThrownBy(() -> run.subscribe(RecordingSink.normal())).isInstanceOf(SubscriberLimitException.class);

        cancelFirst.run();
        run.subscribe(RecordingSink.normal()); // the freed place
    }

    @Test
    @DisplayName(
            "REQ-RUN-011 a subscriber whose write fails frees its place on the next failed write (within 3 s here, 1 s promised)")
    void failedWriteFreesThePlace() {
        // 250 ms sampling: the next tick's write fails. 3 s is the 1 s promise with room for a slow CI machine.
        RunService service = service(held.withSampleInterval(Duration.ofMillis(250)));
        Run run = service.start(BIG, IP);
        run.subscribe(RecordingSink.failing());
        run.subscribe(RecordingSink.failing());

        await().atMost(Duration.ofSeconds(3)).untilAsserted(() -> {
            try {
                run.subscribe(RecordingSink.normal());
            } catch (SubscriberLimitException stillFull) {
                throw new AssertionError("a failed subscriber still holds its place", stillFull);
            }
        });
    }

    @Test
    @DisplayName("REQ-RUN-011 a slow subscriber neither slows the run nor loses the report; old metrics are dropped")
    void slowSubscriberDropsMetricsButKeepsReport() {
        RunService service = service(
                relaxed.withTransfersPerSecond(200).withEventBuffer(1).withSampleInterval(Duration.ofMillis(10)));
        Run run = service.start(new RunRequest(LedgerChoice.CAS, ThreadMode.VIRTUAL, FaultChoice.NONE, 600, 0), IP);
        CountDownLatch release = new CountDownLatch(1);
        RecordingSink stuck = RecordingSink.blockedUntil(release);
        run.subscribe(stuck);

        // the run reaches DONE while the subscriber is still stuck in its first write
        RunReport report = finished(run);
        release.countDown();
        await().atMost(Duration.ofSeconds(10)).until(stuck::isClosed);

        assertThat(report.allPassed()).isTrue();
        assertThat(stuck.count("report")).as("the report is never dropped").isEqualTo(1);
        assertThat(stuck.events.get(stuck.events.size() - 1).name()).isEqualTo("report");
        assertThat(stuck.count("metrics"))
                .as("one in the hand plus one buffered: the rest were dropped, not queued")
                .isLessThanOrEqualTo(2);
    }

    @Test
    @DisplayName("REQ-RUN-011 a subscriber that disconnects, or whose writes fail, does not stop the run")
    void disconnectDoesNotStopTheRun() {
        RunService service = service(relaxed.withTransfersPerSecond(200).withSampleInterval(Duration.ofMillis(10)));
        Run run = service.start(new RunRequest(LedgerChoice.CAS, ThreadMode.VIRTUAL, FaultChoice.NONE, 300, 0), IP);
        run.subscribe(RecordingSink.normal()).run(); // connected, then gone
        run.subscribe(RecordingSink.failing()); // connected, every write throws

        RunReport report = finished(run);

        assertThat(report.stoppedEarly()).isFalse();
        assertThat(report.transfersCompleted()).isEqualTo(300);
        assertThat(report.allPassed()).isTrue();
    }

    // ---- registry, retention, limits through the service --------------------------------------------

    @Test
    @DisplayName("REQ-RUN-012 a report is kept for 10 minutes, then the run is forgotten and its memory released")
    void reportExpiresAfterTenMinutes() {
        MutableClock clock = MutableClock.running(Instant.parse("2026-12-01T10:00:00Z"));
        RunService service = service(relaxed, clock);
        Run run = service.start(new RunRequest(LedgerChoice.CAS, ThreadMode.VIRTUAL, FaultChoice.NONE, 100, 0), IP);
        finished(run);

        clock.advance(Duration.ofMinutes(9).plusSeconds(55));
        assertThat(service.find(run.id())).as("still kept at 9:55").isPresent();

        clock.advance(Duration.ofSeconds(10));
        assertThat(service.find(run.id())).as("forgotten after 10 minutes").isEmpty();
        assertThat(service.storedRuns()).isZero();
    }

    @Test
    @DisplayName("REQ-RUN-012 the retention clock starts when the run finishes, not when it starts")
    void retentionCountsFromFinish() {
        MutableClock clock = MutableClock.running(Instant.parse("2026-12-01T10:00:00Z"));
        RunService service = service(held.withDeadline(Duration.ofSeconds(1)), clock);
        Run run = service.start(BIG, IP);
        clock.advance(Duration.ofSeconds(2)); // past the 1 s deadline: it stops; nobody asks meanwhile
        finished(run);
        clock.advance(Duration.ofMinutes(8)); // 8 min after the finish

        assertThat(service.find(run.id())).isPresent();
    }

    @Test
    @DisplayName("REQ-RUN-003 an unknown run id is not found, and so is an id that is not a valid run id")
    void unknownRunIsNotFound() {
        RunService service = service(relaxed);

        assertThat(service.find("r-00000000")).isEmpty();
        assertThat(service.find("x".repeat(65))).isEmpty();
        assertThat(service.find("r/../x")).isEmpty();
        assertThat(service.find("")).isEmpty();
    }

    @Test
    @DisplayName("REQ-RUN-007 concurrent starts of real runs admit exactly two and refuse the rest BUSY")
    void concurrentStartsAdmitExactlyTwoRuns() throws Exception {
        RunService service = service(held.withMaxActive(2));
        int threads = 40;
        CountDownLatch go = new CountDownLatch(1);
        AtomicInteger admitted = new AtomicInteger();
        List<RunRefusedException> refusals = Collections.synchronizedList(new ArrayList<>());
        List<Thread> pool = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            String ip = "198.51.100." + i;
            pool.add(Thread.ofPlatform().start(() -> {
                try {
                    go.await();
                    service.start(BIG, ip);
                    admitted.incrementAndGet();
                } catch (RunRefusedException refused) {
                    refusals.add(refused);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }));
        }
        go.countDown();
        for (Thread thread : pool) {
            assertThat(thread.join(Duration.ofSeconds(30))).isTrue();
        }

        assertThat(admitted.get()).isEqualTo(2);
        assertThat(refusals).hasSize(threads - 2).allSatisfy(refused -> {
            assertThat(refused.limit()).isEqualTo(RunLimits.Limit.BUSY);
            assertThat(refused.retryAfterSeconds()).isEqualTo(5);
        });
    }

    @Test
    @DisplayName("REQ-RUN-007 a run whose engine cannot be built releases its active slot")
    void failedStartReleasesTheSlot() {
        AtomicInteger calls = new AtomicInteger();
        RunService service = new RunService(relaxed.withMaxActive(1), Clock.systemUTC(), (properties, listener) -> {
            if (calls.getAndIncrement() == 0) {
                throw new IllegalStateException("engine would not start");
            }
            return SettlementRuntime.start(properties, listener);
        });
        services.add(service);
        try {
            service.start(QUICK, IP); // fails now, or in the background: either way the slot must come back
        } catch (RuntimeException expected) {
            // the failure may surface to the caller or only to the run
        }

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            try {
                assertThat(service.start(QUICK, IP)).isNotNull();
            } catch (RunRefusedException busy) {
                throw new AssertionError("the failed run still holds the only active slot", busy);
            }
        });
    }

    @Test
    @DisplayName("REQ-RUN-007 a finished run releases its active slot")
    void finishedRunReleasesTheSlot() {
        RunService service = service(relaxed.withMaxActive(1));
        finished(service.start(QUICK, IP));

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            try {
                service.start(QUICK, IP);
            } catch (RunRefusedException busy) {
                throw new AssertionError("a finished run still holds the only active slot", busy);
            }
        });
    }

    @Test
    @DisplayName("REQ-RUN-006 through the service: the sixth run from one IP in an hour is refused with Retry-After")
    void perIpLimitThroughTheService() {
        MutableClock clock = MutableClock.running(Instant.parse("2026-12-01T10:00:00Z"));
        RunService service = service(relaxed.withPerIpPerHour(5), clock);
        for (int i = 0; i < 5; i++) {
            service.start(QUICK, IP);
            clock.advance(Duration.ofMinutes(10));
        }

        assertThatThrownBy(() -> service.start(QUICK, IP))
                .isInstanceOfSatisfying(RunRefusedException.class, refused -> {
                    assertThat(refused.limit()).isEqualTo(RunLimits.Limit.PER_IP);
                    // the oldest start was 50 minutes and a few real milliseconds ago: 600 s, rounded up
                    assertThat(refused.retryAfterSeconds()).isEqualTo(600);
                });
    }

    // ---- shutdown -----------------------------------------------------------------------------------

    @Test
    @DisplayName("REQ-API-006 closing the service ends a running run and its streams, and refuses new runs")
    void closeEndsRunsAndStreams() {
        long before = runOwnedThreads();
        RunService service = service(held);
        Run run = service.start(BIG, IP);
        assertThat(run.status())
                .as("the run is still going when the service closes")
                .isEqualTo(RunStatus.RUNNING);
        RecordingSink sink = RecordingSink.normal();
        run.subscribe(sink);

        long start = System.nanoTime();
        service.close();
        Duration took = Duration.ofNanos(System.nanoTime() - start);

        assertThat(took).as("close() took %s", took).isLessThan(Duration.ofSeconds(8));
        await().atMost(Duration.ofSeconds(5)).until(sink::isClosed);
        assertThat(sink.count("report")).isLessThanOrEqualTo(1);
        assertThatThrownBy(() -> service.start(BIG, IP)).isInstanceOf(IllegalStateException.class);
        await().atMost(Duration.ofSeconds(10))
                .untilAsserted(() -> assertThat(runOwnedThreads())
                        .as("run-owned threads after close")
                        .isLessThanOrEqualTo(before));
    }
}
