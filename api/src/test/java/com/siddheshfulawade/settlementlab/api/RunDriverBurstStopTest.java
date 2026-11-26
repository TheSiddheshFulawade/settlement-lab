package com.siddheshfulawade.settlementlab.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.siddheshfulawade.settlementlab.domain.Outcome;
import com.siddheshfulawade.settlementlab.gl.OutboxPublisher;
import com.siddheshfulawade.settlementlab.pipeline.SettlementEngine;
import com.siddheshfulawade.settlementlab.pipeline.ThreadMode;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * L-3: the driver's catch-up burst (up to 100 submits, each of which may wait the 100 ms offer timeout
 * when the queue is full) must notice the deadline and a stop request between submits, not after the burst.
 *
 * <p>Deterministic: the fake engine's submit blocks on a semaphore the test holds, so the driver is parked
 * inside a burst at a known submit count. The test then moves the clock (or ends the run), opens the gate
 * and counts how many submits still follow. No sleeping to synchronise.
 */
class RunDriverBurstStopTest {

    private static final RunRequest BIG =
            new RunRequest(LedgerChoice.LOCKING, ThreadMode.VIRTUAL, FaultChoice.NONE, 20_000, 0);

    /** Submits let through before the gate closes; the first one waits 50 ms so a later burst is a full 100. */
    private static final int FREE_SUBMITS = 30;

    /** A few more are fine (the one in flight); the bug lets the rest of a 100 burst through. */
    private static final int ALLOWED_EXTRA = 3;

    private final RunsProperties properties = RunsProperties.defaults()
            .withMaxActive(5)
            .withPerIpPerHour(1_000)
            .withGlobalPerHour(1_000)
            .withTransfersPerSecond(1_000_000)
            .withDeadline(Duration.ofHours(1))
            .withSampleInterval(Duration.ofHours(1));

    private final MutableClock clock = MutableClock.running(Instant.parse("2026-12-01T10:00:00Z"));
    private final Semaphore gate = new Semaphore(0);
    private final AtomicInteger submits = new AtomicInteger();
    private RunService service;

    @AfterEach
    void tearDown() {
        gate.release(1_000_000); // never leave a driver parked
        if (service != null) {
            service.close();
        }
    }

    /** A runtime whose engine answers QUEUE_FULL, and blocks from submit number FREE_SUBMITS + 1 on. */
    private SettlementRuntime gatedRuntime() {
        SettlementEngine engine = mock(SettlementEngine.class);
        when(engine.submit(any())).thenAnswer(call -> {
            int n = submits.incrementAndGet();
            if (n == 1) {
                LockSupport.parkNanos(Duration.ofMillis(50).toNanos()); // later turns find a big backlog: a 100 burst
            } else if (n > FREE_SUBMITS) {
                gate.tryAcquire(20, TimeUnit.SECONDS);
            }
            return CompletableFuture.completedFuture(Outcome.QUEUE_FULL);
        });
        when(engine.drain(any())).thenReturn(true);
        OutboxPublisher outbox = mock(OutboxPublisher.class);
        when(outbox.drain(any())).thenReturn(true);
        SettlementRuntime runtime = mock(SettlementRuntime.class);
        when(runtime.engine()).thenReturn(engine);
        when(runtime.outbox()).thenReturn(outbox);
        return runtime;
    }

    private Run startAndParkInsideABurst() {
        SettlementRuntime runtime = gatedRuntime();
        service = new RunService(properties, clock, (settings, listener) -> runtime);
        Run run = service.start(BIG, "198.51.100.91");
        await().atMost(Duration.ofSeconds(20)).until(() -> submits.get() >= FREE_SUBMITS + 1);
        return run;
    }

    @Test
    @DisplayName(
            "REQ-RUN-005 the deadline passing inside a burst of slow submits stops the run after the one in flight")
    void deadlineInsideABurstStopsTheSubmits() {
        Run run = startAndParkInsideABurst();

        clock.advance(Duration.ofHours(2)); // past the 1 h deadline
        int atDeadline = submits.get();
        gate.release(1_000_000);
        run.awaitDriver(TimeUnit.SECONDS.toNanos(20));

        assertThat(submits.get() - atDeadline)
                .as("submits after the deadline (the rest of a 100 burst must not follow)")
                .isLessThanOrEqualTo(ALLOWED_EXTRA);
    }

    @Test
    @DisplayName("REQ-RUN-005 a run ended from outside during a burst of slow submits stops after the one in flight")
    void endingTheRunInsideABurstStopsTheSubmits() {
        Run run = startAndParkInsideABurst();

        run.end(clock.instant());
        int atEnd = submits.get();
        gate.release(1_000_000);
        run.awaitDriver(TimeUnit.SECONDS.toNanos(20));

        assertThat(submits.get() - atEnd).as("submits after the run was ended").isLessThanOrEqualTo(ALLOWED_EXTRA);
    }

    @Test
    @DisplayName(
            "REQ-RUN-005 closing the service during a burst of slow submits stops the driver after the one in flight")
    void closingTheServiceInsideABurstStopsTheSubmits() throws Exception {
        Run run = startAndParkInsideABurst();

        Thread closer = Thread.ofVirtual().start(service::close); // close() ends the run, then waits for the driver
        await().atMost(Duration.ofSeconds(10)).until(run::stopRequested);
        int atClose = submits.get();
        gate.release(1_000_000);
        closer.join(Duration.ofSeconds(20));
        run.awaitDriver(TimeUnit.SECONDS.toNanos(20));

        assertThat(submits.get() - atClose).as("submits after close() began").isLessThanOrEqualTo(ALLOWED_EXTRA);
    }
}
