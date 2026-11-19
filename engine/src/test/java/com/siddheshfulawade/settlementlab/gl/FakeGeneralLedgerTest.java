package com.siddheshfulawade.settlementlab.gl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.siddheshfulawade.settlementlab.domain.AccountId;
import com.siddheshfulawade.settlementlab.domain.Money;
import com.siddheshfulawade.settlementlab.domain.Posting;
import com.siddheshfulawade.settlementlab.domain.TransferId;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class FakeGeneralLedgerTest {

    private static Posting posting(long sequence) {
        return new Posting(sequence, TransferId.of("t-" + sequence), AccountId.of(1), AccountId.of(2), Money.of(10));
    }

    @Test
    @DisplayName("REQ-LAB-012 with no faults every posting is recorded once")
    void recordsPostings() {
        var gl = new FakeGeneralLedger(FaultProfile.NONE, 1);

        assertThat(gl.publish(posting(1))).isEqualTo(GeneralLedgerPort.Ack.RECORDED);
        assertThat(gl.publish(posting(2))).isEqualTo(GeneralLedgerPort.Ack.RECORDED);

        assertThat(gl.entries()).containsExactly(posting(1), posting(2));
        assertThat(gl.calls()).isEqualTo(2);
    }

    @Test
    @DisplayName("REQ-LAB-012 a repeated publish is detected and counted, never stored twice")
    void detectsDuplicatePublish() {
        var gl = new FakeGeneralLedger(FaultProfile.NONE, 1);
        gl.publish(posting(1));

        assertThat(gl.publish(posting(1))).isEqualTo(GeneralLedgerPort.Ack.ALREADY_RECORDED);

        assertThat(gl.entries()).hasSize(1);
        assertThat(gl.duplicatePublishes()).isEqualTo(1);
    }

    // TODO-ME: write `refusesConflictingRepublish`: publishing a posting whose transfer id is already
    //  recorded but with a different amount must throw IllegalStateException and keep the first
    //  entry. Check it: the test passes; then delete the `!existing.equals(posting)` branch in
    //  FakeGeneralLedger.record and see it fail (the second call returns ALREADY_RECORDED instead).

    @Test
    @DisplayName("REQ-LAB-013 FLAKY fails about 10 % with errors and loses about 5 % of acks, repeatably from a seed")
    void flakyIsRepeatable() {
        var first = new FakeGeneralLedger(FaultProfile.FLAKY.withLatency(Duration.ZERO), 42);
        var second = new FakeGeneralLedger(FaultProfile.FLAKY.withLatency(Duration.ZERO), 42);

        for (long i = 1; i <= 2_000; i++) {
            publishQuietly(first, posting(i));
            publishQuietly(second, posting(i));
        }

        assertThat(first.errors()).isBetween(120L, 280L);
        assertThat(first.lostAcks()).isBetween(50L, 150L);
        assertThat(first.errors()).isEqualTo(second.errors());
        assertThat(first.lostAcks()).isEqualTo(second.lostAcks());
        // A lost ack means the entry WAS recorded: only errors are missing.
        assertThat(first.entries()).hasSize(2_000 - (int) first.errors());
    }

    @Test
    @DisplayName("REQ-LAB-013 a lost ack throws TIMEOUT although the entry was recorded")
    void lostAckRecordsThenTimesOut() {
        var everyAckLost = new FaultProfile("LOSE_ALL", 0, 100, Duration.ZERO, Duration.ZERO, Duration.ZERO);
        var gl = new FakeGeneralLedger(everyAckLost, 1);

        assertThatThrownBy(() -> gl.publish(posting(1)))
                .isInstanceOfSatisfying(
                        GeneralLedgerException.class,
                        e -> assertThat(e.kind()).isEqualTo(GeneralLedgerException.Kind.TIMEOUT));
        assertThat(gl.entries()).containsExactly(posting(1));
    }

    @Test
    @DisplayName("REQ-LAB-013 OUTAGE refuses every call inside its window and none outside it")
    void outageWindow() {
        AtomicLong now = new AtomicLong();
        var gl = new FakeGeneralLedger(FaultProfile.outage(Duration.ofSeconds(1), Duration.ofSeconds(2)), 1, now::get);

        assertThat(gl.publish(posting(1))).isEqualTo(GeneralLedgerPort.Ack.RECORDED);
        now.set(Duration.ofMillis(1_500).toNanos());
        assertThatThrownBy(() -> gl.publish(posting(2)))
                .isInstanceOfSatisfying(
                        GeneralLedgerException.class,
                        e -> assertThat(e.kind()).isEqualTo(GeneralLedgerException.Kind.OUTAGE));
        now.set(Duration.ofSeconds(3).toNanos());
        assertThat(gl.publish(posting(2))).isEqualTo(GeneralLedgerPort.Ack.RECORDED);
        assertThat(gl.outageRejections()).isEqualTo(1);
    }

    @Test
    @DisplayName("REQ-LAB-013 added latency is applied to each call")
    void addsLatency() {
        var gl = new FakeGeneralLedger(FaultProfile.NONE.withLatency(Duration.ofMillis(20)), 1);

        long start = System.nanoTime();
        gl.publish(posting(1));

        assertThat(Duration.ofNanos(System.nanoTime() - start)).isGreaterThanOrEqualTo(Duration.ofMillis(20));
    }

    @Test
    @DisplayName("REQ-LAB-013 latency is a minimum on every call, even where timers wake early (Windows)")
    void latencyIsAMinimumOnEveryCall() {
        Duration latency = Duration.ofMillis(3);
        var gl = new FakeGeneralLedger(FaultProfile.NONE.withLatency(latency), 1);

        for (long i = 1; i <= 25; i++) {
            long start = System.nanoTime();
            gl.publish(posting(i));
            assertThat(Duration.ofNanos(System.nanoTime() - start))
                    .as("call %d", i)
                    .isGreaterThanOrEqualTo(latency);
        }
    }

    private static void publishQuietly(FakeGeneralLedger gl, Posting posting) {
        try {
            gl.publish(posting);
        } catch (GeneralLedgerException expected) {
            // counted by the fake
        }
    }
}
