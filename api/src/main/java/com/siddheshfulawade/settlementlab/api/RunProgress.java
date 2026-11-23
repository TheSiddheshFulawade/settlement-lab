package com.siddheshfulawade.settlementlab.api;

import com.siddheshfulawade.settlementlab.domain.Outcome;
import com.siddheshfulawade.settlementlab.domain.TransferId;
import com.siddheshfulawade.settlementlab.pipeline.EngineMetrics;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.concurrent.atomic.LongAdder;

/**
 * What one run has seen so far: outcomes, latencies, duplicates, and the live numbers of a
 * {@code metrics} event. Written by engine worker threads (as each outcome arrives) and by the driver,
 * read by the sampler thread and, after the drain, by the report.
 *
 * <p>Invariant: every writer is lock-free and never waits, and a reader never needs a consistent
 * snapshot across fields (these are statistics, the invariants are checked on the ledger itself). The
 * latency slots hold {@code nanos + 1} so that 0 means "claimed but not yet written": a reader that
 * races a writer skips that slot instead of counting a bogus zero.
 */
final class RunProgress {

    private static final long RATE_WINDOW_NANOS = 1_000_000_000L;

    private final SettlementRuntime runtime;
    private final Clock clock;
    private final Instant startedAt;

    private final AtomicLongArray latencies;
    private final AtomicInteger recorded = new AtomicInteger();
    private final Set<TransferId> posted = ConcurrentHashMap.newKeySet();
    private final LongAdder completed = new LongAdder();
    private final LongAdder duplicatesSent = new LongAdder();

    /** Recent (nanoTime, posted) points for the 1 s throughput. Guarded by {@code this}. */
    private final List<long[]> recentPosted = new ArrayList<>();

    RunProgress(SettlementRuntime runtime, Clock clock, Instant startedAt, int transfers) {
        this.runtime = runtime;
        this.clock = clock;
        this.startedAt = startedAt;
        this.latencies = new AtomicLongArray(transfers);
        recentPosted.add(new long[] {System.nanoTime(), 0});
    }

    void duplicateSent() {
        duplicatesSent.increment();
    }

    /** One transfer has its outcome ({@code outcome} is null if the engine failed it). */
    void completed(TransferId id, Outcome outcome, long latencyNanos) {
        if (outcome == Outcome.POSTED) {
            posted.add(id); // a duplicate's outcome is the original's, so a repeat adds nothing
        }
        int slot = recorded.getAndIncrement();
        if (slot < latencies.length()) {
            latencies.set(slot, latencyNanos + 1);
        }
        completed.increment();
    }

    /** The ids the run's ledger accepted: what POSTINGS_MATCH_ACCEPTED compares with the postings. */
    Collection<TransferId> postedIds() {
        return posted;
    }

    long completedCount() {
        return completed.sum();
    }

    long duplicatesSentCount() {
        return duplicatesSent.sum();
    }

    /** Nearest-rank percentile of the latencies so far, in microseconds (0 if none). */
    long percentileMicros(int percentile) {
        int available = Math.min(recorded.get(), latencies.length());
        long[] values = new long[available];
        int count = 0;
        for (int i = 0; i < available; i++) {
            long stored = latencies.get(i);
            if (stored > 0) {
                values[count++] = stored - 1;
            }
        }
        if (count == 0) {
            return 0;
        }
        Arrays.sort(values, 0, count);
        int rank = (int) Math.ceil(percentile / 100.0 * count);
        return values[Math.max(rank, 1) - 1] / 1_000;
    }

    /** The numbers of one {@code metrics} event, as of now. */
    RunMetrics metrics() {
        EngineMetrics engine = runtime.engine().metrics();
        long rejected =
                engine.rejectedFunds() + engine.rejectedInvalid() + engine.queueFull() + engine.rejectedContention();
        return new RunMetrics(
                Duration.between(startedAt, clock.instant()).toMillis(),
                engine.submitted(),
                engine.posted(),
                rejected,
                engine.duplicates(),
                postedPerSecondOverLastSecond(engine.posted()),
                percentileMicros(99),
                engine.queueDepth(),
                runtime.properties().queueCapacity(),
                runtime.resilient().retries(),
                runtime.resilient().breakerState(),
                runtime.outbox().outboxSize());
    }

    /**
     * Postings per second between now and the oldest point at most about one second back. Measured on
     * {@code System.nanoTime()}, not the run's {@link Clock}: a rate needs a clock that only moves forward
     * at the real speed, and a test clock may jump.
     */
    private synchronized long postedPerSecondOverLastSecond(long postedNow) {
        long now = System.nanoTime();
        recentPosted.add(new long[] {now, postedNow});
        while (recentPosted.size() > 2 && now - recentPosted.get(1)[0] >= RATE_WINDOW_NANOS) {
            recentPosted.remove(0);
        }
        long[] oldest = recentPosted.get(0);
        long elapsed = now - oldest[0];
        return elapsed <= 0 ? 0 : (postedNow - oldest[1]) * 1_000_000_000L / elapsed;
    }
}
