package com.siddheshfulawade.settlementlab.api;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Who may start a run now (REQ-RUN-006, 007): a per-visitor window, a global window and a cap on runs
 * active at once. All in memory: the service runs as one instance (ADR-0002), so these counts are exact.
 *
 * <p>Invariant: one lock guards all the state below, and {@link #tryStart} checks and records inside it.
 * So two racing starts can never both take the last slot (check-then-act would let both through), and a
 * refusal changes nothing: a refused start takes no per-visitor, global or active slot.
 *
 * <p>Windows are sliding and exactly one hour: a start that is exactly one hour old has left the window.
 * Time comes from the injected {@link Clock} (wall time), never from the system, so tests move it.
 *
 * <p>Memory: a visitor's entry is removed as soon as its last start leaves the window, on any later call
 * (not only a call from that visitor). At most {@code globalPerHour} starts are held, so at most that many
 * visitors are. Keys are held only here and are never logged, thrown in a message or printed.
 */
final class RunLimits {

    /** Which limit refused a start. The order is the order of checking. */
    enum Limit {
        PER_IP,
        BUSY,
        GLOBAL
    }

    private static final Duration WINDOW = Duration.ofHours(1);
    private static final long BUSY_RETRY_SECONDS = 5;

    /** One admitted start: its time, and whose it was (so the visitor's own list can be trimmed too). */
    private record Start(String clientKey, Instant at) {}

    private final Clock clock;
    private final int perIpPerHour;
    private final int globalPerHour;
    private final int maxActive;

    private final ReentrantLock lock = new ReentrantLock();
    /** All starts inside the window, oldest first. Guarded by lock. */
    private final Deque<Start> globalStarts = new ArrayDeque<>();
    /** Each visitor's starts inside the window, oldest first; no empty lists. Guarded by lock. */
    private final Map<String, Deque<Instant>> clientStarts = new HashMap<>();
    /** Admitted runs that have not released. Guarded by lock. */
    private int active;

    RunLimits(Clock clock, int perIpPerHour, int globalPerHour, int maxActive) {
        this.clock = clock;
        this.perIpPerHour = perIpPerHour;
        this.globalPerHour = globalPerHour;
        this.maxActive = maxActive;
    }

    /** Admits the start or says which limit refused it and when to retry. Never blocks on I/O. */
    Admission tryStart(String clientKey) {
        lock.lock();
        try {
            Instant now = clock.instant();
            forgetOldStarts(now);
            Deque<Instant> own = clientStarts.get(clientKey);
            if (own != null && own.size() >= perIpPerHour) {
                return Admission.refused(Limit.PER_IP, secondsUntilLeaves(own.peekFirst(), now));
            }
            if (active >= maxActive) {
                return Admission.refused(Limit.BUSY, BUSY_RETRY_SECONDS);
            }
            if (globalStarts.size() >= globalPerHour) {
                return Admission.refused(
                        Limit.GLOBAL,
                        secondsUntilLeaves(globalStarts.peekFirst().at(), now));
            }
            globalStarts.addLast(new Start(clientKey, now));
            clientStarts.computeIfAbsent(clientKey, key -> new ArrayDeque<>()).addLast(now);
            active++;
            return Admission.admitted(this::releaseActive);
        } finally {
            lock.unlock();
        }
    }

    /** Visitors currently remembered (only those with a start inside the window). */
    int trackedClients() {
        lock.lock();
        try {
            return clientStarts.size();
        } finally {
            lock.unlock();
        }
    }

    /** Called by {@link Admission#release()}, which guarantees at most once per admission. */
    private void releaseActive() {
        lock.lock();
        try {
            active--;
        } finally {
            lock.unlock();
        }
    }

    /** Drops starts that are one hour old or older, and any visitor left with none. Caller holds the lock. */
    private void forgetOldStarts(Instant now) {
        Instant cutoff = now.minus(WINDOW);
        while (!globalStarts.isEmpty() && !globalStarts.peekFirst().at().isAfter(cutoff)) {
            Start old = globalStarts.pollFirst();
            // The visitor's own list is the global list's subsequence, so the same start is at its head.
            Deque<Instant> own = clientStarts.get(old.clientKey());
            own.pollFirst();
            if (own.isEmpty()) {
                clientStarts.remove(old.clientKey());
            }
        }
    }

    /** Whole seconds until {@code oldest} is one hour old, rounded up, at least 1. */
    private static long secondsUntilLeaves(Instant oldest, Instant now) {
        long nanos = Duration.between(now, oldest.plus(WINDOW)).toNanos();
        long seconds = (nanos + 999_999_999L) / 1_000_000_000L;
        return Math.max(1, seconds);
    }
}
