package com.siddheshfulawade.settlementlab.gl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.siddheshfulawade.settlementlab.domain.AccountId;
import com.siddheshfulawade.settlementlab.domain.Money;
import com.siddheshfulawade.settlementlab.domain.Posting;
import com.siddheshfulawade.settlementlab.domain.TransferId;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
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
        final List<Long> recorded = new CopyOnWriteArrayList<>();

        @Override
        public Ack publish(Posting posting) {
            if (down.get()) {
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

        assertThat(port.recorded).containsExactly(1L);
        assertThat(publisher.outboxSize()).isZero();
        assertThat(publisher.outboxPeak()).isZero();
        assertThat(publisher.published()).isEqualTo(1);
    }

    @Test
    @DisplayName(
            "REQ-LAB-011 a failed publish goes to the outbox; the relay delivers it, in order, once the GL is back")
    void outboxRelaysInOrder() {
        port.down.set(true);
        for (long i = 1; i <= 5; i++) {
            publisher.publish(posting(i));
        }
        assertThat(publisher.outboxSize()).isEqualTo(5);
        assertThat(publisher.drain(Duration.ofMillis(30)))
                .as("cannot drain while the GL is down")
                .isFalse();

        port.down.set(false);

        assertThat(publisher.drain(WAIT)).isTrue();
        assertThat(port.recorded).containsExactly(1L, 2L, 3L, 4L, 5L);
        assertThat(publisher.outboxPeak()).isEqualTo(5);
        assertThat(publisher.failedAttempts()).isPositive();
    }

    @Test
    @DisplayName("REQ-LAB-011 while the outbox holds entries, new postings queue behind them")
    void newPostingsQueueBehindTheOutbox() {
        port.down.set(true);
        publisher.publish(posting(1));
        await().atMost(WAIT).until(() -> publisher.failedAttempts() >= 2); // relay has retried at least once
        port.down.set(false);
        publisher.publish(posting(2));

        assertThat(publisher.drain(WAIT)).isTrue();
        assertThat(port.recorded).containsExactly(1L, 2L);
    }

    @Test
    @DisplayName("close stops the relay and is idempotent")
    void closeIsIdempotent() {
        publisher.close();
        publisher.close();
        assertThat(publisher.drain(Duration.ZERO)).isTrue();
    }
}
