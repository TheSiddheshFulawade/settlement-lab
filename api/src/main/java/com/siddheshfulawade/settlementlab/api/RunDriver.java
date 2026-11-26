package com.siddheshfulawade.settlementlab.api;

import com.siddheshfulawade.settlementlab.domain.AccountId;
import com.siddheshfulawade.settlementlab.domain.InvariantChecker;
import com.siddheshfulawade.settlementlab.domain.InvariantReport;
import com.siddheshfulawade.settlementlab.domain.Money;
import com.siddheshfulawade.settlementlab.domain.Posting;
import com.siddheshfulawade.settlementlab.domain.Transfer;
import com.siddheshfulawade.settlementlab.domain.TransferId;
import com.siddheshfulawade.settlementlab.pipeline.SettlementEngine;
import com.siddheshfulawade.settlementlab.runner.Workload;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.locks.LockSupport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The work of one run, on its own thread: submit the workload at a steady rate, drain, check the
 * invariants, publish the report, and close everything the run opened.
 *
 * <p>Wall time: the 20 s deadline and the drain limit are read from the injected {@link Clock}, because
 * "wall time" is the promise (a process that lost its CPU must still stop on time), and a test must be
 * able to move it. The pacing uses {@code System.nanoTime()}, which only moves forward at real speed:
 * it decides when the next transfer is due, not how long the run may last.
 *
 * <p>Invariant (cleanup on every path): everything the run owns (engine workers, outbox relay and
 * senders) is closed in {@code finally}, whether the run finished, was stopped, failed, or was ended by
 * the application, and the active slot is given back last. The sampler and the streams belong to
 * {@link Run}, which closes them in {@code finish} and {@code end}.
 */
final class RunDriver implements Runnable {

    /** Fixed, so the same request always gives the same workload (REQ-RUN-009). */
    static final int ACCOUNTS = 1_000;

    static final long SEED = 20_261_119L;
    private static final int HOT_ACCOUNTS = 20;
    private static final int HOT_PERCENT = 20;

    /** The report must be out within 60 s of the 202 (REQ-RUN-005); the drain gives up this long before. */
    private static final Duration REPORT_BOUND = Run.REPORT_LIMIT;

    private static final Duration DRAIN_MARGIN = Duration.ofSeconds(5);
    private static final Duration DRAIN_SLICE = Duration.ofMillis(100);

    /** A wait is never longer than this, so a deadline or a stop request is noticed promptly. */
    private static final long MAX_PARK_NANOS = Duration.ofMillis(100).toNanos();

    /** After a stall the driver catches up by at most this many transfers at once, not the whole backlog. */
    private static final int MAX_BURST = 100;

    private static final Logger LOG = LoggerFactory.getLogger(RunDriver.class);

    private final Run run;
    private final RunRequest request;
    private final RunsProperties properties;
    private final Clock clock;
    private final SettlementRuntime runtime;
    private final RunProgress progress;
    private final Workload workload;

    RunDriver(
            Run run,
            RunRequest request,
            RunsProperties properties,
            Clock clock,
            SettlementRuntime runtime,
            RunProgress progress) {
        this.run = run;
        this.request = request;
        this.properties = properties;
        this.clock = clock;
        this.runtime = runtime;
        this.progress = progress;
        this.workload = new Workload(
                ACCOUNTS, request.transfers(), HOT_ACCOUNTS, HOT_PERCENT, request.duplicatePercent(), SEED);
    }

    @Override
    public void run() {
        try {
            boolean stoppedEarly = submitAtSteadyRate();
            drain();
            if (!run.stopRequested()) {
                Instant drainedAt = clock.instant();
                RunReport report = buildReport(stoppedEarly, drainedAt);
                run.finish(report, clock.instant());
            }
        } catch (RuntimeException failure) {
            failed(failure);
        } catch (Error failure) {
            // An Error also ends this run: otherwise it would sit RUNNING for 60 s. It is then rethrown (the
            // JVM's business, not ours to swallow); the finally below still runs.
            failed(failure);
            throw failure;
        } finally {
            // Each step runs even if the one before it throws: runtime closed, slot given back, thread forgotten.
            try {
                closeRuntime();
            } finally {
                try {
                    run.releaseSlot();
                } finally {
                    run.driverDone();
                }
            }
        }
    }

    private void failed(Throwable failure) {
        run.end(clock.instant()); // first: if logging throws, the viewers must not wait 60 s for the expiry
        // The class name only: a message could hold anything, and nothing here is about a visitor.
        LOG.warn("run {} failed: {}", run.id(), failure.getClass().getSimpleName());
    }

    // ---- submitting ----------------------------------------------------------------------------------

    /**
     * Submits transfer 0, 1, 2, ... transfer {@code i} is due at {@code schedule + i * interval}. Each turn
     * submits everything that is due (a wake-up may be a millisecond late, so a couple at a time), then
     * waits for the next one: never a busy spin. Invariant: the catch-up after a stall is bounded by
     * {@link #MAX_BURST}; beyond that the schedule is moved forward instead, so the rate stays steady.
     *
     * @return true if the deadline stopped the run before every transfer was submitted
     */
    private boolean submitAtSteadyRate() {
        int total = request.transfers();
        long interval = Math.max(1, 1_000_000_000L / properties.transfersPerSecond());
        Instant deadline = run.startedAt().plus(properties.deadline());
        long schedule = System.nanoTime();
        int submitted = 0;
        while (submitted < total) {
            if (run.stopRequested()) {
                return false; // ended from outside: no report will be made
            }
            if (!clock.instant().isBefore(deadline)) {
                return true;
            }
            long due = (System.nanoTime() - schedule) / interval + 1;
            long behind = due - submitted;
            if (behind > MAX_BURST) {
                schedule += (behind - MAX_BURST) * interval;
                due = submitted + MAX_BURST;
            }
            long upTo = Math.min(total, due);
            // Invariant: at most one submit follows the deadline or a stop request. A submit can block for the
            // offer timeout when the queue is full, so a burst of 100 may not run on unchecked (10 s).
            while (submitted < upTo && !run.stopRequested() && clock.instant().isBefore(deadline)) {
                submit(submitted);
                submitted++;
            }
            if (submitted < total) {
                long wait = schedule + submitted * interval - System.nanoTime();
                if (wait > 0) {
                    LockSupport.parkNanos(Math.min(wait, MAX_PARK_NANOS));
                }
            }
        }
        return false;
    }

    private void submit(int index) {
        Transfer transfer = workload.transfer(index);
        // A duplicate resends an earlier transfer unchanged, so its id is not "t-<its own index>".
        if (!transfer.id().value().equals("t-" + index)) {
            progress.duplicateSent();
        }
        long sent = System.nanoTime();
        runtime.engine()
                .submit(transfer)
                .whenComplete((outcome, error) ->
                        progress.completed(transfer.id(), error == null ? outcome : null, System.nanoTime() - sent));
    }

    // ---- draining and reporting ------------------------------------------------------------------------

    /**
     * Waits until the engine has processed everything and the outbox has delivered everything, but only
     * until {@code start + 55 s} on the clock: the report has to be out within 60 s. If the general ledger
     * has not caught up by then, the report says so (GL_MATCHES_POSTINGS fails): an honest failure, not a
     * hang.
     */
    private void drain() {
        Instant giveUpAt = run.startedAt().plus(REPORT_BOUND).minus(DRAIN_MARGIN);
        SettlementEngine engine = runtime.engine();
        while (!run.stopRequested()) {
            if (engine.drain(DRAIN_SLICE) && runtime.outbox().drain(DRAIN_SLICE)) {
                return;
            }
            if (!clock.instant().isBefore(giveUpAt)) {
                return;
            }
        }
    }

    private RunReport buildReport(boolean stoppedEarly, Instant drainedAt) {
        SettlementEngine engine = runtime.engine();
        Map<AccountId, Money> opening = workload.openingBalances();
        Map<AccountId, Money> closing = engine.snapshot();
        List<Posting> postings = engine.postings();

        InvariantReport checked = InvariantChecker.checkRun(
                opening,
                closing,
                postings,
                progress.postedIds(),
                runtime.generalLedger().entries());

        long durationMs = Duration.between(run.startedAt(), drainedAt).toMillis();
        Set<TransferId> distinctIds = new HashSet<>();
        postings.forEach(posting -> distinctIds.add(posting.transferId()));
        return new RunReport(
                run.id(),
                RunStatus.DONE,
                stoppedEarly,
                request,
                durationMs,
                progress.completedCount(),
                total(opening),
                total(closing),
                progress.duplicatesSentCount(),
                postings.size() - distinctIds.size(), // postings beyond one per transfer id
                postings.size() * 1_000L / Math.max(durationMs, 1), // paced rate, not capacity
                progress.percentileMicros(50),
                progress.percentileMicros(99),
                runtime.resilient().retries(),
                runtime.resilient().breakerOpens(),
                checked.results().stream().map(RunReport.InvariantLine::of).toList(),
                checked.allPassed());
    }

    private static long total(Map<AccountId, Money> balances) {
        long sum = 0;
        for (Money money : balances.values()) {
            sum += money.minorUnits();
        }
        return sum;
    }

    private void closeRuntime() {
        try {
            runtime.close(); // engine first, then the outbox: see SettlementRuntime
        } catch (RuntimeException failure) {
            LOG.warn(
                    "run {}: closing its engine failed: {}",
                    run.id(),
                    failure.getClass().getSimpleName());
        }
    }
}
