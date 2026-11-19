package com.siddheshfulawade.settlementlab.gl;

import com.siddheshfulawade.settlementlab.domain.Posting;
import com.siddheshfulawade.settlementlab.domain.TransferId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.locks.LockSupport;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.LongSupplier;
import java.util.random.RandomGenerator;

/**
 * An in-memory general ledger that misbehaves on purpose, by {@link FaultProfile} (REQ-LAB-013).
 * Entries are keyed by transfer id, so a repeated publish is detected and counted, never stored twice
 * and never hidden.
 *
 * <p>Repeatable: fault draws come from one seeded generator, so the same seed and the same order of
 * calls give the same faults. (With concurrent callers the order itself can vary.)
 */
public final class FakeGeneralLedger implements GeneralLedgerPort {

    private final FaultProfile profile;
    private final LongSupplier nanoClock;
    private final long createdAt;
    private final ReentrantLock randomLock = new ReentrantLock();
    private final RandomGenerator random; // not thread-safe: use only under randomLock
    private final Map<TransferId, Posting> entries = new ConcurrentHashMap<>();

    private final LongAdder calls = new LongAdder();
    private final LongAdder errors = new LongAdder();
    private final LongAdder lostAcks = new LongAdder();
    private final LongAdder outageRejections = new LongAdder();
    private final LongAdder duplicatePublishes = new LongAdder();

    public FakeGeneralLedger(FaultProfile profile, long seed) {
        this(profile, seed, System::nanoTime);
    }

    /** {@code nanoClock} lets tests move time through the outage window without waiting. */
    public FakeGeneralLedger(FaultProfile profile, long seed, LongSupplier nanoClock) {
        this.profile = profile;
        this.random = new SplittableRandom(seed);
        this.nanoClock = nanoClock;
        this.createdAt = nanoClock.getAsLong();
    }

    @Override
    public Ack publish(Posting posting) {
        calls.increment();
        if (!profile.latency().isZero()) {
            LockSupport.parkNanos(profile.latency().toNanos()); // simulated network time
        }
        if (inOutage()) {
            outageRejections.increment();
            throw new GeneralLedgerException(GeneralLedgerException.Kind.OUTAGE, "general ledger unavailable");
        }
        int roll = nextPercent();
        if (roll < profile.errorPercent()) {
            errors.increment();
            throw new GeneralLedgerException(GeneralLedgerException.Kind.ERROR, "general ledger refused the call");
        }
        Ack ack = record(posting);
        if (roll < profile.errorPercent() + profile.lostAckPercent()) {
            lostAcks.increment();
            throw new GeneralLedgerException(GeneralLedgerException.Kind.TIMEOUT, "ack lost after recording");
        }
        return ack;
    }

    private Ack record(Posting posting) {
        Posting existing = entries.putIfAbsent(posting.transferId(), posting);
        if (existing == null) {
            return Ack.RECORDED;
        }
        if (!existing.equals(posting)) {
            throw new IllegalStateException("conflicting posting for " + posting.transferId());
        }
        duplicatePublishes.increment();
        return Ack.ALREADY_RECORDED;
    }

    private boolean inOutage() {
        if (profile.outageLength().isZero()) {
            return false;
        }
        long sinceCreation = nanoClock.getAsLong() - createdAt;
        long start = profile.outageStart().toNanos();
        return sinceCreation >= start
                && sinceCreation < start + profile.outageLength().toNanos();
    }

    private int nextPercent() {
        randomLock.lock();
        try {
            return random.nextInt(100);
        } finally {
            randomLock.unlock();
        }
    }

    /** Every stored entry, in ledger sequence order. */
    public List<Posting> entries() {
        List<Posting> copy = new ArrayList<>(entries.values());
        copy.sort(Comparator.comparingLong(Posting::sequence));
        return List.copyOf(copy);
    }

    public long calls() {
        return calls.sum();
    }

    public long errors() {
        return errors.sum();
    }

    public long lostAcks() {
        return lostAcks.sum();
    }

    public long outageRejections() {
        return outageRejections.sum();
    }

    /** Publishes of a transfer id that was already stored: visible, not hidden. */
    public long duplicatePublishes() {
        return duplicatePublishes.sum();
    }
}
