package com.siddheshfulawade.settlementlab.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The run limits with a hand-moved clock and no HTTP (REQ-RUN-006, 007).
 *
 * <p>ASSUMED API (package-private, {@code com.siddheshfulawade.settlementlab.api}):
 * {@code RunLimits(Clock clock, int perIpPerHour, int globalPerHour, int maxActive)},
 * {@code Admission tryStart(String clientIp)}, {@code int trackedClients()};
 * {@code Admission}: {@code boolean admitted()}, {@code Optional<RunLimits.Limit> refusal()},
 * {@code long retryAfterSeconds()}, {@code void release()} (idempotent; a refusal releases nothing);
 * {@code enum RunLimits.Limit {PER_IP, GLOBAL, BUSY}}. The window is one hour; an entry that is exactly
 * one hour old has left the window.
 */
class RunLimitsTest {

    private static final String A = "198.51.100.1";
    private static final String B = "198.51.100.2";

    private final MutableClock clock = new MutableClock(Instant.parse("2026-12-01T10:00:00Z"));

    private RunLimits limits(int perIp, int global, int active) {
        return new RunLimits(clock, perIp, global, active);
    }

    /** Starts and finishes at once, so only the window counts, not the active cap. */
    private static void startAndFinish(RunLimits limits, String ip) {
        Admission admission = limits.tryStart(ip);
        assertThat(admission.admitted()).as("start for %s", ip).isTrue();
        admission.release();
    }

    private static void assertRefused(Admission admission, RunLimits.Limit limit) {
        assertThat(admission.admitted()).isFalse();
        assertThat(admission.refusal()).contains(limit);
    }

    @Test
    @DisplayName("REQ-RUN-006 the sixth start by one IP within an hour is refused PER_IP")
    void sixthStartIsRefused() {
        RunLimits limits = limits(5, 60, 2);
        for (int i = 0; i < 5; i++) {
            startAndFinish(limits, A);
        }

        assertRefused(limits.tryStart(A), RunLimits.Limit.PER_IP);
    }

    @Test
    @DisplayName("REQ-RUN-006 Retry-After is the exact seconds until the oldest start leaves the window")
    void retryAfterIsExact() {
        RunLimits limits = limits(5, 60, 2);
        for (int i = 0; i < 5; i++) {
            startAndFinish(limits, A);
            clock.advance(Duration.ofMinutes(10));
        }
        // starts were at 0, 10, 20, 30 and 40 minutes; now it is 50 minutes: the oldest leaves at 60
        Admission refused = limits.tryStart(A);

        assertRefused(refused, RunLimits.Limit.PER_IP);
        assertThat(refused.retryAfterSeconds()).isEqualTo(600);
    }

    @Test
    @DisplayName("REQ-RUN-006 Retry-After rounds a partial second up, never down")
    void retryAfterRoundsUp() {
        RunLimits limits = limits(1, 60, 2);
        startAndFinish(limits, A);
        clock.advance(Duration.ofHours(1).minusMillis(1_500)); // 1.5 s remain

        Admission refused = limits.tryStart(A);

        assertRefused(refused, RunLimits.Limit.PER_IP);
        assertThat(refused.retryAfterSeconds()).isEqualTo(2);
    }

    @Test
    @DisplayName("REQ-RUN-006 a start exactly one hour old has left the window; one millisecond younger has not")
    void windowBoundaryIsExactlyOneHour() {
        RunLimits limits = limits(1, 60, 2);
        startAndFinish(limits, A);
        clock.advance(Duration.ofHours(1).minusMillis(1));

        Admission justBefore = limits.tryStart(A);
        assertRefused(justBefore, RunLimits.Limit.PER_IP);
        assertThat(justBefore.retryAfterSeconds()).isEqualTo(1);

        clock.advance(Duration.ofMillis(1));
        assertThat(limits.tryStart(A).admitted()).isTrue();
    }

    @Test
    @DisplayName("REQ-RUN-006 the window slides: when the oldest leaves, exactly one slot frees")
    void windowSlides() {
        RunLimits limits = limits(5, 60, 2);
        startAndFinish(limits, A); // t = 0
        clock.advance(Duration.ofMinutes(30));
        for (int i = 0; i < 4; i++) {
            startAndFinish(limits, A); // t = 30 min, four starts
        }
        clock.advance(Duration.ofMinutes(30)); // t = 60: the first start has left, four remain

        startAndFinish(limits, A);

        assertRefused(limits.tryStart(A), RunLimits.Limit.PER_IP);
    }

    @Test
    @DisplayName("REQ-RUN-006 each IP has its own window")
    void ipsAreIndependent() {
        RunLimits limits = limits(1, 60, 2);
        startAndFinish(limits, A);

        assertThat(limits.tryStart(B).admitted()).isTrue();
    }

    @Test
    @DisplayName("REQ-RUN-007 a start over the global cap is refused GLOBAL with Retry-After until the next slot")
    void globalCapRefusesWithExactRetryAfter() {
        RunLimits limits = limits(100, 3, 10);
        for (int i = 0; i < 3; i++) {
            startAndFinish(limits, "203.0.113." + i);
            clock.advance(Duration.ofMinutes(10)); // starts at 0, 10, 20 minutes; now 30
        }

        Admission refused = limits.tryStart("203.0.113.99");

        assertRefused(refused, RunLimits.Limit.GLOBAL);
        assertThat(refused.retryAfterSeconds()).isEqualTo(Duration.ofMinutes(30).toSeconds());
    }

    @Test
    @DisplayName("REQ-RUN-007 a third run while two are active is refused BUSY with Retry-After 5")
    void busyWhenTwoActive() {
        RunLimits limits = limits(5, 60, 2);
        Admission first = limits.tryStart(A);
        Admission second = limits.tryStart(B);

        Admission third = limits.tryStart("198.51.100.3");

        assertRefused(third, RunLimits.Limit.BUSY);
        assertThat(third.retryAfterSeconds()).isEqualTo(5);
        first.release();
        assertThat(limits.tryStart("198.51.100.3").admitted()).isTrue();
        assertThat(second.admitted()).isTrue();
    }

    @Test
    @DisplayName("REQ-RUN-007 a BUSY refusal consumes no per-IP slot")
    void busyRefusalKeepsIpSlots() {
        RunLimits limits = limits(2, 60, 1);
        Admission holding = limits.tryStart(A); // IP A: 1 of 2 used, the only active slot taken
        for (int i = 0; i < 10; i++) {
            assertRefused(limits.tryStart(A), RunLimits.Limit.BUSY);
        }
        holding.release();

        startAndFinish(limits, A); // the second of two: must still be allowed
        assertRefused(limits.tryStart(A), RunLimits.Limit.PER_IP);
    }

    @Test
    @DisplayName("REQ-RUN-006 a PER_IP refusal consumes no global slot and takes no active slot")
    void perIpRefusalKeepsOtherSlots() {
        RunLimits limits = limits(1, 3, 2);
        startAndFinish(limits, A); // global 1 of 3
        Admission activeB = limits.tryStart(B); // global 2 of 3, active 1 of 2
        for (int i = 0; i < 10; i++) {
            assertRefused(limits.tryStart(A), RunLimits.Limit.PER_IP);
        }

        Admission activeC = limits.tryStart("198.51.100.3"); // global 3 of 3, active 2 of 2

        assertThat(activeC.admitted())
                .as("refusals must not have used a global or active slot")
                .isTrue();
        assertThat(activeB.admitted()).isTrue();
        assertRefused(limits.tryStart("198.51.100.4"), RunLimits.Limit.BUSY);
    }

    @Test
    @DisplayName("REQ-RUN-007 a GLOBAL refusal consumes no per-IP slot")
    void globalRefusalKeepsIpSlots() {
        RunLimits limits = limits(1, 1, 5);
        startAndFinish(limits, B); // t = 0: the only global slot (another visitor)
        clock.advance(Duration.ofMinutes(10));
        for (int i = 0; i < 5; i++) {
            assertRefused(limits.tryStart(A), RunLimits.Limit.GLOBAL);
        }
        clock.advance(Duration.ofMinutes(50)); // t = 60: the global slot is free again

        // IP A may have 1 start per hour, and the refusals above must not have counted as starts
        assertThat(limits.tryStart(A).admitted()).isTrue();
    }

    @Test
    @DisplayName("REQ-RUN-007 releasing a refusal frees nothing; releasing twice frees one slot")
    void releaseOnlyGivesBackWhatWasTaken() {
        RunLimits limits = limits(10, 60, 2);
        Admission first = limits.tryStart(A);
        Admission second = limits.tryStart(B);
        Admission refused = limits.tryStart("198.51.100.3");

        refused.release();
        assertRefused(limits.tryStart("198.51.100.4"), RunLimits.Limit.BUSY);

        first.release();
        first.release(); // a second release must not hand out a second slot
        assertThat(limits.tryStart("198.51.100.5").admitted()).isTrue();
        assertRefused(limits.tryStart("198.51.100.6"), RunLimits.Limit.BUSY);
        assertThat(second.admitted()).isTrue();
    }

    @Test
    @DisplayName(
            "REQ-RUN-006 after an IP's window passes the limiter holds no entry for it, even with no request from it")
    void expiredIpsAreForgotten() {
        RunLimits limits = limits(5, 60, 5);
        startAndFinish(limits, A);
        startAndFinish(limits, B);
        assertThat(limits.trackedClients()).isEqualTo(2);
        clock.advance(Duration.ofHours(1));

        startAndFinish(limits, "198.51.100.3"); // any later call, from another visitor

        assertThat(limits.trackedClients())
                .as("only the visitor who just started is held")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("REQ-RUN-006 a refused start leaves no entry for its IP")
    void refusedIpsAreNotRemembered() {
        RunLimits limits = limits(5, 60, 1);
        Admission holding = limits.tryStart(A);

        assertRefused(limits.tryStart(B), RunLimits.Limit.BUSY);

        assertThat(limits.trackedClients()).isEqualTo(1);
        holding.release();
    }

    @Test
    @DisplayName("REQ-RUN-007 when several limits apply the order is PER_IP, then BUSY, then GLOBAL")
    void refusalOrder() {
        RunLimits busyAndGlobal = limits(5, 1, 1);
        Admission holding = busyAndGlobal.tryStart(A); // uses the only global slot and the only active slot
        assertRefused(busyAndGlobal.tryStart(B), RunLimits.Limit.BUSY);
        holding.release();
        assertRefused(busyAndGlobal.tryStart(B), RunLimits.Limit.GLOBAL);

        RunLimits ipAndBusy = limits(1, 60, 1);
        Admission active = ipAndBusy.tryStart(A);
        assertRefused(ipAndBusy.tryStart(A), RunLimits.Limit.PER_IP);
        active.release();
    }
}
