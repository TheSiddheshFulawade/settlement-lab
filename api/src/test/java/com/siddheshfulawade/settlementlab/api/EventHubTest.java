package com.siddheshfulawade.settlementlab.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.siddheshfulawade.settlementlab.domain.AccountId;
import com.siddheshfulawade.settlementlab.domain.Money;
import com.siddheshfulawade.settlementlab.domain.Outcome;
import com.siddheshfulawade.settlementlab.domain.Transfer;
import com.siddheshfulawade.settlementlab.domain.TransferId;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class EventHubTest {

    private static final Duration WAIT = Duration.ofSeconds(10);

    private final EventHub hub = new EventHub(4);

    @AfterEach
    void close() {
        hub.close();
    }

    /** A sink that records what it gets; optionally blocks until released, or fails. */
    private static final class TestSink implements EventHub.Sink {
        final List<String> received = new CopyOnWriteArrayList<>();
        final CountDownLatch release = new CountDownLatch(1);
        final AtomicBoolean closed = new AtomicBoolean();
        final boolean blocks;
        final boolean fails;

        TestSink(boolean blocks, boolean fails) {
            this.blocks = blocks;
            this.fails = fails;
        }

        @Override
        public void send(OutcomeEvent event) throws Exception {
            if (fails) {
                throw new java.io.IOException("client went away");
            }
            if (blocks) {
                release.await();
            }
            received.add(event.id() + "=" + event.outcome());
        }

        @Override
        public void close() {
            closed.set(true);
        }
    }

    private static Transfer transfer(int i) {
        return new Transfer(TransferId.of("e-" + i), AccountId.of(1), AccountId.of(2), Money.of(i));
    }

    @Test
    @DisplayName("REQ-API-005 every subscriber gets every outcome, in order")
    void deliversInOrder() {
        TestSink first = new TestSink(false, false);
        TestSink second = new TestSink(false, false);
        hub.subscribe(first);
        hub.subscribe(second);

        for (int i = 0; i < 3; i++) {
            hub.onOutcome(transfer(i), Outcome.POSTED);
        }

        await().atMost(WAIT).until(() -> first.received.size() == 3 && second.received.size() == 3);
        assertThat(first.received).containsExactly("e-0=POSTED", "e-1=POSTED", "e-2=POSTED");
        assertThat(second.received).isEqualTo(first.received);
    }

    @Test
    @DisplayName("REQ-API-005 a slow subscriber is dropped; the engine's thread never waits, others keep receiving")
    void slowSubscriberIsDropped() {
        TestSink slow = new TestSink(true, false); // blocks on its first event, so its buffer fills
        TestSink fast = new TestSink(false, false);
        hub.subscribe(slow);
        hub.subscribe(fast);
        Duration slowestCall = Duration.ZERO;

        for (int i = 0; i < 20; i++) {
            long start = System.nanoTime();
            hub.onOutcome(transfer(i), Outcome.POSTED);
            Duration call = Duration.ofNanos(System.nanoTime() - start);
            slowestCall = call.compareTo(slowestCall) > 0 ? call : slowestCall;
            int expected = i + 1;
            await().atMost(WAIT).until(() -> fast.received.size() == expected); // fast keeps up
        }

        assertThat(slowestCall).as("onOutcome never blocks on a client").isLessThan(Duration.ofMillis(500));
        assertThat(fast.received).hasSize(20);
        assertThat(slow.closed).isTrue();
        assertThat(hub.subscribers()).isEqualTo(1);
        assertThat(hub.dropped()).isEqualTo(1);
        slow.release.countDown();
    }

    @Test
    @DisplayName("REQ-API-005 a subscriber whose connection fails is removed")
    void failingSubscriberIsRemoved() {
        TestSink broken = new TestSink(false, true);
        hub.subscribe(broken);

        hub.onOutcome(transfer(1), Outcome.REJECTED_FUNDS);

        await().atMost(WAIT).until(() -> hub.subscribers() == 0);
        assertThat(broken.closed).isTrue();
    }

    @Test
    @DisplayName("close ends every subscription")
    void closeEndsSubscriptions() {
        TestSink sink = new TestSink(false, false);
        hub.subscribe(sink);

        hub.close();

        await().atMost(WAIT).until(sink.closed::get); // drop() closes on its own virtual thread
        assertThat(hub.subscribers()).isZero();
        hub.onOutcome(transfer(1), Outcome.POSTED); // no subscribers left: nothing happens
    }

    // TODO-ME: write `cancelStopsDelivery`: subscribe a TestSink, call the Runnable that subscribe()
    //  returns, then publish one outcome. The sink must be closed, receive nothing, and subscribers()
    //  must be 0. Check it: the test passes; then make the returned Runnable do nothing and see it fail.
}
