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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Failure paths of the hub: misbehaving sinks must never hurt the engine thread or other clients. */
class EventHubFailureTest {

    private static final Duration WAIT = Duration.ofSeconds(10);

    /** A sink whose send and close can each be made to block or fail. */
    private static final class MisbehavingSink implements EventHub.Sink {
        final List<String> received = new CopyOnWriteArrayList<>();
        final CountDownLatch release = new CountDownLatch(1);
        final AtomicBoolean closeCalled = new AtomicBoolean();
        volatile boolean blockSendUninterruptibly;
        volatile boolean blockInClose;
        volatile boolean throwInClose;

        @Override
        public void send(OutcomeEvent event) throws Exception {
            if (blockSendUninterruptibly) {
                boolean released = false;
                while (!released) {
                    try {
                        released = release.await(WAIT.toMillis(), TimeUnit.MILLISECONDS);
                    } catch (InterruptedException ignoredOnPurpose) {
                        // a write stuck in socket I/O does not react to interrupts either
                    }
                }
            }
            received.add(event.id());
        }

        @Override
        public void close() {
            closeCalled.set(true);
            if (blockInClose) {
                try {
                    release.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            if (throwInClose) {
                throw new IllegalStateException("emitter already completed");
            }
        }
    }

    private static Transfer transfer(int i) {
        return new Transfer(TransferId.of("f-" + i), AccountId.of(1), AccountId.of(2), Money.of(i + 1));
    }

    /** Runs the publish on its own thread so a hang fails the test instead of hanging the build. */
    private static void publishWithin(EventHub hub, int from, int to, Duration bound) throws Exception {
        CompletableFuture<Void> engine = CompletableFuture.runAsync(() -> {
            for (int i = from; i < to; i++) {
                hub.onOutcome(transfer(i), Outcome.POSTED);
            }
        });
        try {
            engine.get(bound.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException hung) {
            throw new AssertionError("onOutcome blocked on a subscriber for more than " + bound, hung);
        }
    }

    @Test
    @DisplayName("REQ-API-005 subscribing while outcomes flood in never throws on the engine thread")
    void subscribeRaceNeverBreaksTheEngineThread() throws Exception {
        try (EventHub hub = new EventHub(1)) {
            List<Throwable> engineFailures = new CopyOnWriteArrayList<>();
            AtomicBoolean stop = new AtomicBoolean();
            CountDownLatch flooding = new CountDownLatch(1);
            Thread engine = new Thread(() -> {
                flooding.countDown();
                int i = 0;
                while (!stop.get()) {
                    try {
                        hub.onOutcome(transfer(i++), Outcome.POSTED);
                    } catch (Throwable t) {
                        engineFailures.add(t);
                    }
                }
            });
            engine.start();
            flooding.await();

            for (int n = 0; n < 5_000 && engineFailures.isEmpty(); n++) {
                hub.subscribe(new MisbehavingSink());
            }
            stop.set(true);
            engine.join(WAIT.toMillis());

            assertThat(engineFailures)
                    .as("exceptions thrown out of onOutcome on the engine thread")
                    .isEmpty();
        }
    }

    @Test
    @DisplayName("REQ-API-005 a sink whose close blocks does not block the engine or the other subscribers")
    void blockingCloseDoesNotBlockTheEngine() throws Exception {
        EventHub hub = new EventHub(1);
        MisbehavingSink stuck = new MisbehavingSink();
        stuck.blockSendUninterruptibly = true; // so its buffer fills and it is dropped
        stuck.blockInClose = true;
        MisbehavingSink healthy = new MisbehavingSink();
        hub.subscribe(stuck);
        hub.subscribe(healthy);

        try {
            // Paced: with a buffer of 1 an unpaced burst would rightly drop the healthy subscriber too.
            for (int i = 0; i < 10; i++) {
                publishWithin(hub, i, i + 1, Duration.ofSeconds(5));
                int expected = i + 1;
                await().atMost(WAIT).until(() -> healthy.received.size() == expected);
            }

            // The close runs on its own thread, so wait for it.
            await("the slow subscriber was dropped").atMost(WAIT).untilTrue(stuck.closeCalled);
        } finally {
            stuck.release.countDown();
            hub.close();
        }
    }

    @Test
    @DisplayName("REQ-API-005 a sink whose close throws does not break onOutcome or the other subscribers")
    void throwingCloseIsContained() throws Exception {
        EventHub hub = new EventHub(1);
        MisbehavingSink bad = new MisbehavingSink();
        bad.blockSendUninterruptibly = true;
        bad.throwInClose = true;
        MisbehavingSink healthy = new MisbehavingSink();
        hub.subscribe(bad);
        hub.subscribe(healthy);

        try {
            AtomicInteger published = new AtomicInteger();
            for (int i = 0; i < 10; i++) {
                hub.onOutcome(transfer(i), Outcome.POSTED); // must not throw
                published.incrementAndGet();
                int expected = published.get();
                await().atMost(WAIT).until(() -> healthy.received.size() == expected);
            }

            await("the failing subscriber was closed").atMost(WAIT).untilTrue(bad.closeCalled);
        } finally {
            bad.release.countDown();
            hub.close();
        }
    }

    @Test
    @DisplayName("REQ-API-005 a sender stuck in a write: onOutcome stays fast, it is dropped, close returns")
    void stuckWriteCannotHoldTheHostage() throws Exception {
        EventHub hub = new EventHub(2);
        MisbehavingSink stuck = new MisbehavingSink();
        stuck.blockSendUninterruptibly = true;
        hub.subscribe(stuck);

        try {
            publishWithin(hub, 0, 20, Duration.ofSeconds(5));
            await().atMost(WAIT).until(() -> hub.subscribers() == 0);
            await("the stuck subscriber was closed").atMost(WAIT).untilTrue(stuck.closeCalled);

            MisbehavingSink stuckAtClose = new MisbehavingSink();
            stuckAtClose.blockSendUninterruptibly = true;
            hub.subscribe(stuckAtClose);
            hub.onOutcome(transfer(100), Outcome.POSTED);
            CompletableFuture<Void> closing = CompletableFuture.runAsync(hub::close);
            try {
                closing.get(5, TimeUnit.SECONDS);
            } catch (TimeoutException hung) {
                throw new AssertionError("hub.close() blocked behind a stuck sender", hung);
            }
        } finally {
            stuck.release.countDown();
            hub.close();
        }
    }

    @Test
    @DisplayName("subscribe after close is refused: the sink is closed and nothing is leaked")
    void subscribeAfterCloseLeaksNothing() {
        EventHub hub = new EventHub(2);
        hub.close();
        MisbehavingSink late = new MisbehavingSink();

        hub.subscribe(late);

        assertThat(late.closeCalled)
                .as("a sink subscribed to a closed hub is closed")
                .isTrue();
        assertThat(hub.subscribers())
                .as("no subscription (and sender thread) left behind")
                .isZero();
    }
}
