package com.siddheshfulawade.settlementlab.api;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The answer of {@link RunLimits#tryStart}: admitted (and holding one active-run slot until released),
 * or refused (holding nothing).
 *
 * <p>Invariant: the active slot is given back exactly once. {@link #release()} flips a flag with
 * compareAndSet, so a run that finishes, is expired and fails all at the same time still frees one slot,
 * not three. A refusal has nothing to give back, and holds no client address (nothing here can print one).
 */
final class Admission {

    private final RunLimits.Limit refusal; // null when admitted
    private final long retryAfterSeconds;
    private final Runnable giveBackSlot;
    private final AtomicBoolean released = new AtomicBoolean();

    private Admission(RunLimits.Limit refusal, long retryAfterSeconds, Runnable giveBackSlot) {
        this.refusal = refusal;
        this.retryAfterSeconds = retryAfterSeconds;
        this.giveBackSlot = giveBackSlot;
    }

    static Admission admitted(Runnable giveBackSlot) {
        return new Admission(null, 0, giveBackSlot);
    }

    static Admission refused(RunLimits.Limit limit, long retryAfterSeconds) {
        return new Admission(limit, retryAfterSeconds, () -> {});
    }

    boolean admitted() {
        return refusal == null;
    }

    Optional<RunLimits.Limit> refusal() {
        return Optional.ofNullable(refusal);
    }

    long retryAfterSeconds() {
        return retryAfterSeconds;
    }

    /** Idempotent. */
    void release() {
        if (released.compareAndSet(false, true)) {
            giveBackSlot.run();
        }
    }
}
