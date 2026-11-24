package com.siddheshfulawade.settlementlab.gl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.siddheshfulawade.settlementlab.domain.AccountId;
import com.siddheshfulawade.settlementlab.domain.Money;
import com.siddheshfulawade.settlementlab.domain.Posting;
import com.siddheshfulawade.settlementlab.domain.TransferId;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class OutboxPublisherTest {

    private static final Duration RETRY = Duration.ofMillis(5);
    private static final Duration WAIT = Duration.ofSeconds(10);

    /** A general ledger that is down until the test brings it up, and records what it received. */
    private static final class SwitchablePort implements GeneralLedgerPort {
        final AtomicBoolean down = new AtomicBoolean();
        final Set<Long> failing = ConcurrentHashMap.newKeySet();
        final List<Long> recorded = new CopyOnWriteArrayList<>();

        @Override
        public Ack publish(Posting posting) {
            if (down.get() || failing.contains(posting.sequence())) {
                throw new GeneralLedgerException(GeneralLedgerException.Kind.OUTAGE, "down");
            }
            recorded.add(posting.sequence());
            return Ack.RECORDED;
        }
    }

    private final SwitchablePort port = new SwitchablePort();
    private final OutboxPublisher publisher = new OutboxPublisher(port, RETRY);

    @AfterEach
    void close() {
        publisher.close();
    }

    private static Posting posting(long sequence) {
        return new Posting(sequence, TransferId.of("t-" + sequence), AccountId.of(1), AccountId.of(2), Money.of(1));
    }

    @Test
    @DisplayName("REQ-LAB-011 a healthy general ledger is published to directly; the outbox stays empty")
    void publishesDirectly() {
        publisher.publish(posting(1));

        assertThat(publisher.drain(WAIT)).isTrue();
        assertThat(port.recorded).containsExactly(1L);
        assertThat(publisher.outboxSize()).isZero();
        assertThat(publisher.outboxPeak()).isZero();
        assertThat(publisher.published()).isEqualTo(1);
    }

    @Test
    @DisplayName("REQ-LAB-011 a failed publish goes to the outbox; the relay delivers every one once the GL is back")
    void outboxRelaysEverything() {
        port.down.set(true);
        for (long i = 1; i <= 5; i++) {
            publisher.publish(posting(i));
        }
        await().atMost(WAIT).until(() -> publisher.outboxSize() == 5);
        assertThat(publisher.drain(Duration.ofMillis(30)))
                .as("cannot drain while the GL is down")
                .isFalse();

        port.down.set(false);

        assertThat(publisher.drain(WAIT)).isTrue();
        // Senders run concurrently, so arrival order is not fixed; the GL is keyed by id, so it need not be.
        assertThat(port.recorded).containsExactlyInAnyOrder(1L, 2L, 3L, 4L, 5L);
        assertThat(publisher.outboxPeak()).isEqualTo(5);
        assertThat(publisher.failedAttempts()).isPositive();
    }

    @Test
    @DisplayName("REQ-LAB-011 a waiting outbox entry does not hold back new postings: they still go direct")
    void newPostingsDoNotWaitBehindTheOutbox() {
        port.failing.add(1L);
        publisher.publish(posting(1));
        await().atMost(WAIT).until(() -> publisher.failedAttempts() >= 2); // relay has retried at least once

        publisher.publish(posting(2));

        // Recorded while posting 1 is still waiting in the outbox.
        await().atMost(WAIT).until(() -> port.recorded.contains(2L));
        assertThat(port.recorded).containsExactly(2L);
        assertThat(publisher.outboxSize()).isEqualTo(1);
        port.failing.clear();
        assertThat(publisher.drain(WAIT)).isTrue();
        assertThat(port.recorded).containsExactly(2L, 1L);
    }

    @Test
    @DisplayName("REQ-LAB-011 publish returns at once even when the general ledger is slow")
    void publishDoesNotWaitForASlowGeneralLedger() throws Exception {
        CountDownLatch gate = new CountDownLatch(1);
        GeneralLedgerPort slow = posting -> {
            try {
                gate.await(); // a general ledger that does not answer until the test says so
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return GeneralLedgerPort.Ack.RECORDED;
        };
        try (var async = new OutboxPublisher(slow, RETRY)) {
            long start = System.nanoTime();
            for (long i = 1; i <= 100; i++) {
                async.publish(posting(i));
            }
            assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(1));
            assertThat(async.drain(Duration.ofMillis(20)))
                    .as("sends are still in flight")
                    .isFalse();

            gate.countDown();
            assertThat(async.drain(WAIT)).isTrue();
            assertThat(async.published()).isEqualTo(100);
        }
    }

    @Test
    @DisplayName("REQ-LAB-011 pendingCount counts postings waiting for a sender or being sent; outboxSize does not")
    void pendingCountIncludesPostingsWaitingForASender() {
        CountDownLatch gate = new CountDownLatch(1);
        GeneralLedgerPort gated = posting -> {
            try {
                gate.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return GeneralLedgerPort.Ack.RECORDED;
        };
        try (var async = new OutboxPublisher(gated, RETRY, 4)) {
            for (long i = 1; i <= 20; i++) {
                async.publish(posting(i));
            }

            assertThat(async.pendingCount())
                    .as("4 being sent + 16 waiting for a sender")
                    .isEqualTo(20);
            assertThat(async.outboxSize()).as("none has failed yet").isZero();

            gate.countDown();
            assertThat(async.drain(WAIT)).isTrue();
            assertThat(async.pendingCount()).isZero();
            assertThat(async.published()).isEqualTo(20);
        }
    }

    @Test
    @DisplayName("REQ-LAB-011 a posting waiting for the relay is counted once in pendingCount, and 0 after the drain")
    void pendingCountCountsAnOutboxEntryOnce() {
        port.down.set(true);
        for (long i = 1; i <= 5; i++) {
            publisher.publish(posting(i));
        }
        await().atMost(WAIT).until(() -> publisher.outboxSize() == 5);
        assertThat(publisher.pendingCount()).isEqualTo(5);

        port.down.set(false);

        assertThat(publisher.drain(WAIT)).isTrue();
        assertThat(publisher.pendingCount()).isZero();
        assertThat(publisher.outboxSize()).isZero();
    }

    @Test
    @DisplayName("close stops the relay and is idempotent")
    void closeIsIdempotent() {
        publisher.close();
        publisher.close();
        assertThat(publisher.drain(Duration.ZERO)).isTrue();
    }
}
