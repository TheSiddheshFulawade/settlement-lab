package com.siddheshfulawade.settlementlab.api;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A clock a test moves by hand, so limits, deadlines and retention are tested with no waiting.
 *
 * <p>{@link #frozen} stands still until {@link #advance}: for exact Retry-After values with no HTTP.
 * {@link #running} follows real time and {@link #advance} adds a jump on top: for tests with live
 * runs, whose pacing and deadlines must keep moving while a "lost CPU" or a late deadline is simulated.
 */
final class MutableClock extends Clock {

    private final Instant start;
    private final long startNanos = System.nanoTime();
    private final boolean running;
    private final AtomicLong jumpNanos = new AtomicLong();

    private MutableClock(Instant start, boolean running) {
        this.start = start;
        this.running = running;
    }

    MutableClock(Instant start) {
        this(start, false);
    }

    static MutableClock frozen(Instant start) {
        return new MutableClock(start, false);
    }

    static MutableClock running(Instant start) {
        return new MutableClock(start, true);
    }

    void advance(Duration amount) {
        jumpNanos.addAndGet(amount.toNanos());
    }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return this;
    }

    @Override
    public Instant instant() {
        long elapsed = running ? System.nanoTime() - startNanos : 0;
        return start.plusNanos(jumpNanos.get() + elapsed);
    }
}
