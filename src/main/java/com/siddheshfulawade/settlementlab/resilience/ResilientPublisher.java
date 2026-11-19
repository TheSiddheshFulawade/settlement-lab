package com.siddheshfulawade.settlementlab.resilience;

import com.siddheshfulawade.settlementlab.domain.Posting;
import com.siddheshfulawade.settlementlab.gl.GeneralLedgerException;
import com.siddheshfulawade.settlementlab.gl.GeneralLedgerPort;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.core.IntervalFunction;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Supplier;

/**
 * A {@link GeneralLedgerPort} that wraps another with a Resilience4j {@link Retry} <em>inside</em> a
 * {@link CircuitBreaker} (SPEC.md section 4.4). Put it between the outbox and the general ledger.
 *
 * <ul>
 *   <li><b>Retry</b> (inner): only on {@link GeneralLedgerException} (transient), with exponential
 *       back-off and jitter. A bug such as a conflicting posting is not retried.
 *   <li><b>Circuit breaker</b> (outer): one publish, retries included, counts as one call. When too
 *       many recent publishes fail, it opens and fails fast for {@code openWait} without touching
 *       the general ledger, then lets a few probe calls through (half-open) and closes if they pass.
 * </ul>
 *
 * Breaker outside retry means an open breaker is never retried: retrying a known-down service only
 * burns back-off time. When this class gives up, it throws, and the outbox keeps the posting.
 */
public final class ResilientPublisher implements GeneralLedgerPort {

    private final GeneralLedgerPort delegate;
    private final Retry retry;
    private final CircuitBreaker breaker;
    private final LongAdder retries = new LongAdder();
    private final LongAdder breakerOpens = new LongAdder();

    public ResilientPublisher(GeneralLedgerPort delegate, ResilienceConfig config) {
        this.delegate = delegate;
        this.retry = Retry.of(
                "general-ledger",
                RetryConfig.custom()
                        .maxAttempts(config.maxAttempts())
                        .intervalFunction(IntervalFunction.ofExponentialRandomBackoff(
                                config.initialBackoff(), config.backoffMultiplier(), config.randomizationFactor()))
                        .retryExceptions(GeneralLedgerException.class)
                        .build());
        this.breaker = CircuitBreaker.of(
                "general-ledger",
                CircuitBreakerConfig.custom()
                        .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                        .slidingWindowSize(config.slidingWindowSize())
                        .minimumNumberOfCalls(config.minimumCalls())
                        .failureRateThreshold(config.failureRatePercent())
                        .waitDurationInOpenState(config.openWait())
                        .permittedNumberOfCallsInHalfOpenState(config.halfOpenCalls())
                        .recordExceptions(GeneralLedgerException.class)
                        .build());
        retry.getEventPublisher().onRetry(event -> retries.increment());
        breaker.getEventPublisher().onStateTransition(event -> {
            if (event.getStateTransition().getToState() == CircuitBreaker.State.OPEN) {
                breakerOpens.increment();
            }
        });
    }

    @Override
    public Ack publish(Posting posting) {
        Supplier<Ack> call = () -> delegate.publish(posting);
        Supplier<Ack> retried = Retry.decorateSupplier(retry, call);
        Supplier<Ack> guarded = CircuitBreaker.decorateSupplier(breaker, retried);
        try {
            return guarded.get();
        } catch (CallNotPermittedException open) {
            throw new GeneralLedgerException(GeneralLedgerException.Kind.CIRCUIT_OPEN, "circuit breaker is open");
        }
    }

    /** Retries made (not counting first attempts). */
    public long retries() {
        return retries.sum();
    }

    /** How many times the breaker went to OPEN. */
    public long breakerOpens() {
        return breakerOpens.sum();
    }

    /** CLOSED, OPEN or HALF_OPEN. */
    public String breakerState() {
        return breaker.getState().name();
    }
}
