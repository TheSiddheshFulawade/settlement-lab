package com.siddheshfulawade.settlementlab.resilience;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import com.siddheshfulawade.settlementlab.domain.AccountId;
import com.siddheshfulawade.settlementlab.domain.Money;
import com.siddheshfulawade.settlementlab.domain.Posting;
import com.siddheshfulawade.settlementlab.domain.TransferId;
import com.siddheshfulawade.settlementlab.gl.GeneralLedgerException;
import com.siddheshfulawade.settlementlab.gl.GeneralLedgerPort;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ResilientPublisherTest {

    private static final Posting POSTING =
            new Posting(1, TransferId.of("t-1"), AccountId.of(1), AccountId.of(2), Money.of(5));

    /** Tiny waits so tests run fast: 1 ms back-off, a window of 4 calls, 100 ms open wait. */
    private static final ResilienceConfig FAST =
            new ResilienceConfig(3, Duration.ofMillis(1), 2.0, 0.5, 4, 4, 50, Duration.ofMillis(100), 2);

    /** A port that answers with whatever the test plugs in, and counts its calls. */
    private static final class ScriptedPort implements GeneralLedgerPort {
        final AtomicInteger calls = new AtomicInteger();
        volatile Supplier<Ack> behaviour = () -> Ack.RECORDED;

        @Override
        public Ack publish(Posting posting) {
            calls.incrementAndGet();
            return behaviour.get();
        }
    }

    private static Supplier<GeneralLedgerPort.Ack> failing() {
        return () -> {
            throw new GeneralLedgerException(GeneralLedgerException.Kind.ERROR, "down");
        };
    }

    private final ScriptedPort port = new ScriptedPort();
    private final ResilientPublisher publisher = new ResilientPublisher(port, FAST);

    @Test
    @DisplayName("REQ-LAB-011 a healthy call passes straight through")
    void healthyCallPassesThrough() {
        assertThat(publisher.publish(POSTING)).isEqualTo(GeneralLedgerPort.Ack.RECORDED);

        assertThat(port.calls).hasValue(1);
        assertThat(publisher.retries()).isZero();
        assertThat(publisher.breakerState()).isEqualTo("CLOSED");
    }

    @Test
    @DisplayName("REQ-LAB-011 a transient failure is retried with back-off until it succeeds")
    void retriesTransientFailures() {
        AtomicInteger attempt = new AtomicInteger();
        port.behaviour = () -> {
            if (attempt.incrementAndGet() < 3) {
                throw new GeneralLedgerException(GeneralLedgerException.Kind.TIMEOUT, "slow");
            }
            return GeneralLedgerPort.Ack.RECORDED;
        };

        assertThat(publisher.publish(POSTING)).isEqualTo(GeneralLedgerPort.Ack.RECORDED);

        assertThat(port.calls).hasValue(3);
        assertThat(publisher.retries()).isEqualTo(2);
    }

    @Test
    @DisplayName("REQ-LAB-011 after the last attempt the failure is passed on, for the outbox to keep")
    void givesUpAfterMaxAttempts() {
        port.behaviour = failing();

        assertThatThrownBy(() -> publisher.publish(POSTING)).isInstanceOf(GeneralLedgerException.class);

        assertThat(port.calls).hasValue(3);
    }

    @Test
    @DisplayName("REQ-LAB-011 a non-transient error (a bug) is not retried")
    void doesNotRetryBugs() {
        port.behaviour = () -> {
            throw new IllegalStateException("conflicting posting");
        };

        assertThatThrownBy(() -> publisher.publish(POSTING)).isInstanceOf(IllegalStateException.class);

        assertThat(port.calls).hasValue(1);
    }

    @Test
    @DisplayName("REQ-LAB-011 repeated failures open the breaker; then calls fail fast without touching the GL")
    void breakerOpensAndFailsFast() {
        port.behaviour = failing();
        for (int i = 0; i < 4; i++) {
            assertThatThrownBy(() -> publisher.publish(POSTING)).isInstanceOf(GeneralLedgerException.class);
        }
        assertThat(publisher.breakerState()).isEqualTo("OPEN");
        assertThat(publisher.breakerOpens()).isEqualTo(1);
        int callsWhenOpened = port.calls.get();

        assertThatThrownBy(() -> publisher.publish(POSTING))
                .isInstanceOfSatisfying(
                        GeneralLedgerException.class,
                        e -> assertThat(e.kind()).isEqualTo(GeneralLedgerException.Kind.CIRCUIT_OPEN));
        assertThat(port.calls).hasValue(callsWhenOpened);
    }

    @Test
    @DisplayName("REQ-LAB-011 after the open wait, successful probe calls close the breaker again")
    void breakerClosesAfterRecovery() {
        port.behaviour = failing();
        for (int i = 0; i < 4; i++) {
            assertThatThrownBy(() -> publisher.publish(POSTING)).isInstanceOf(GeneralLedgerException.class);
        }
        port.behaviour = () -> GeneralLedgerPort.Ack.RECORDED;

        await().atMost(Duration.ofSeconds(5)).until(() -> {
            try {
                publisher.publish(POSTING);
            } catch (GeneralLedgerException stillOpen) {
                // keep probing until the open wait has passed
            }
            return publisher.breakerState().equals("CLOSED");
        });
        assertThat(publisher.breakerOpens()).isEqualTo(1);
    }

    // TODO-ME: write `openBreakerIsNotRetried`: once the breaker is OPEN, one publish must make zero
    //  calls to the port AND add nothing to retries() (retrying a breaker that is open only burns
    //  back-off time). Check it: the test passes; then in ResilientPublisher swap the decoration
    //  order (Retry outside the CircuitBreaker) and see retries() grow while the breaker is open.
}
