package com.siddheshfulawade.settlementlab.gl;

import java.time.Duration;
import java.util.Objects;

/**
 * Which faults the {@link FakeGeneralLedger} injects (REQ-LAB-013). Rates are whole percentages of
 * calls; each call draws once from a seeded random generator.
 *
 * @param errorPercent calls refused with {@code ERROR}; nothing stored
 * @param lostAckPercent calls stored but answered with {@code TIMEOUT}: the sender never hears the ack
 *     and will publish again, which the general ledger must detect (timeouts and duplicate acks)
 * @param latency added to every call
 * @param outageStart when the outage window opens, measured from the general ledger's creation
 * @param outageLength how long every call is refused with {@code OUTAGE}; zero for no outage
 */
public record FaultProfile(
        String name,
        int errorPercent,
        int lostAckPercent,
        Duration latency,
        Duration outageStart,
        Duration outageLength) {

    public static final FaultProfile NONE = new FaultProfile("NONE", 0, 0, Duration.ZERO, Duration.ZERO, Duration.ZERO);

    /** 10 % errors, 5 % timeouts (lost acks), 1 ms added latency. */
    public static final FaultProfile FLAKY =
            new FaultProfile("FLAKY", 10, 5, Duration.ofMillis(1), Duration.ZERO, Duration.ZERO);

    /** 30 % of acks lost, so about a third of postings are published twice. */
    public static final FaultProfile DUPLICATE_ACKS =
            new FaultProfile("DUPLICATE_ACKS", 0, 30, Duration.ZERO, Duration.ZERO, Duration.ZERO);

    public FaultProfile {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(latency, "latency");
        Objects.requireNonNull(outageStart, "outageStart");
        Objects.requireNonNull(outageLength, "outageLength");
        if (errorPercent < 0 || lostAckPercent < 0 || errorPercent + lostAckPercent > 100) {
            throw new IllegalArgumentException("rates must be 0 to 100 % and add up to at most 100 %");
        }
        if (latency.isNegative() || outageStart.isNegative() || outageLength.isNegative()) {
            throw new IllegalArgumentException("durations must not be negative");
        }
    }

    /** Fails everything from {@code start} for {@code length}. */
    public static FaultProfile outage(Duration start, Duration length) {
        return new FaultProfile("OUTAGE", 0, 0, Duration.ZERO, start, length);
    }

    public FaultProfile withLatency(Duration newLatency) {
        return new FaultProfile(name, errorPercent, lostAckPercent, newLatency, outageStart, outageLength);
    }

    @Override
    public String toString() {
        return name;
    }
}
