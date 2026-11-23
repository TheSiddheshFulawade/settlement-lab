package com.siddheshfulawade.settlementlab.pipeline;

import com.siddheshfulawade.settlementlab.domain.AccountId;
import com.siddheshfulawade.settlementlab.domain.ApplyResult;
import com.siddheshfulawade.settlementlab.domain.InvalidReason;
import com.siddheshfulawade.settlementlab.domain.Ledger;
import com.siddheshfulawade.settlementlab.domain.Money;
import com.siddheshfulawade.settlementlab.domain.Outcome;
import com.siddheshfulawade.settlementlab.domain.Posting;
import com.siddheshfulawade.settlementlab.domain.PostingPublisher;
import com.siddheshfulawade.settlementlab.domain.Transfer;
import com.siddheshfulawade.settlementlab.domain.TransferValidator;
import com.siddheshfulawade.settlementlab.idempotency.IdempotencyStore;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * The one type callers (and v2's API) use. Flow per transfer:
 *
 * <ol>
 *   <li>claim the id ({@link IdempotencyStore}); a duplicate gets the original outcome (REQ-LAB-008)
 *   <li>validate; an invalid transfer is answered now and never queued (REQ-LAB-004)
 *   <li>offer to the bounded queue for at most {@code offerTimeout}; if full, answer
 *       {@code QUEUE_FULL} and release the id so it can be retried (REQ-LAB-009)
 *   <li>a worker applies it to the ledger, completes the caller's future, hands the posting to the
 *       {@link PostingPublisher} (general ledger, via an outbox), then tells the listener
 * </ol>
 *
 * The caller's answer does not wait for the general ledger: the ledger posting is the source of
 * truth and the general ledger catches up (SPEC.md section 4.4).
 *
 * Free of framework types so v2 can wrap it (SPEC.md section 4.7).
 */
public final class SettlementEngine implements AutoCloseable {

    private final Ledger ledger;
    private final EngineConfig config;
    private final SettlementListener listener;
    private final PostingPublisher publisher;
    private final IdempotencyStore idempotency = new IdempotencyStore();
    private final BoundedIntake intake;
    private final WorkerPool pool;
    private final InFlight inFlight = new InFlight();
    private final AtomicBoolean closed = new AtomicBoolean();

    /**
     * Shared by submitters from their closed check until their offer is done; taken exclusively by
     * close to flip {@code closed}. So once close holds it, no submit is half-way, and no job can
     * reach the queue after close emptied it.
     */
    private final ReentrantReadWriteLock closing = new ReentrantReadWriteLock();

    private final LongAdder submitted = new LongAdder();
    private final LongAdder posted = new LongAdder();
    private final LongAdder rejectedFunds = new LongAdder();
    private final LongAdder rejectedInvalid = new LongAdder();
    private final LongAdder queueFull = new LongAdder();
    private final LongAdder rejectedContention = new LongAdder();
    private final LongAdder failed = new LongAdder();
    private final LongAdder listenerErrors = new LongAdder();
    private final LongAdder publishErrors = new LongAdder();

    /** An engine that publishes postings nowhere: for tests and ledger-only benchmarks. */
    public SettlementEngine(Ledger ledger, EngineConfig config, SettlementListener listener) {
        this(ledger, config, listener, PostingPublisher.NONE);
    }

    public SettlementEngine(
            Ledger ledger, EngineConfig config, SettlementListener listener, PostingPublisher publisher) {
        this.ledger = Objects.requireNonNull(ledger, "ledger");
        this.config = Objects.requireNonNull(config, "config");
        this.listener = Objects.requireNonNull(listener, "listener");
        this.publisher = Objects.requireNonNull(publisher, "publisher");
        this.intake = new BoundedIntake(config.queueCapacity());
        this.pool = new WorkerPool(config.threadMode(), config.workers(), intake, this::process);
        pool.start();
    }

    /**
     * Submits a transfer. Never blocks longer than {@code offerTimeout}.
     *
     * @throws IllegalStateException after {@link #close()}
     */
    public CompletionStage<Outcome> submit(Transfer transfer) {
        closing.readLock().lock();
        try {
            if (closed.get()) {
                throw new IllegalStateException("engine is closed");
            }
            return accept(transfer);
        } finally {
            closing.readLock().unlock();
        }
    }

    private CompletionStage<Outcome> accept(Transfer transfer) {
        submitted.increment();
        IdempotencyStore.Claim claim = idempotency.claim(transfer.id());
        if (!claim.owner()) {
            return claim.outcome();
        }
        if (validate(transfer).isPresent()) {
            rejectedInvalid.increment();
            claim.outcome().complete(Outcome.REJECTED_INVALID);
            return claim.outcome();
        }
        // Count it before it is visible to workers, so drain can never see zero while it waits.
        inFlight.increment();
        if (!intake.offer(new Job(transfer, claim), config.offerTimeout())) {
            inFlight.decrement();
            answerQueueFull(new Job(transfer, claim));
        }
        return claim.outcome();
    }

    /** Why a transfer would be rejected as invalid, or empty if it is valid (REQ-LAB-004). */
    public Optional<InvalidReason> validate(Transfer transfer) {
        return TransferValidator.validate(transfer, ledger::hasAccount);
    }

    /** Runs on a worker thread. */
    private void process(Job job) {
        try {
            ApplyResult result;
            try {
                result = ledger.applyWithResult(job.transfer());
            } catch (RuntimeException e) {
                // Nothing was applied (the ledger is atomic), so the id may be retried.
                failed.increment();
                idempotency.release(job.transfer().id(), job.claim());
                job.claim().outcome().completeExceptionally(e);
                return;
            }
            Outcome outcome = result.outcome();
            count(outcome);
            if (outcome == Outcome.REJECTED_CONTENTION) {
                // Invariant: nothing was stored, so the id must not stay claimed. Release it before
                // completing the future, so a caller that retries at once is processed fresh.
                idempotency.release(job.transfer().id(), job.claim());
            }
            job.claim().outcome().complete(outcome);
            result.posting().ifPresent(this::publish);
            tellListener(job.transfer(), outcome);
        } finally {
            inFlight.decrement();
        }
    }

    private void count(Outcome outcome) {
        switch (outcome) {
            case POSTED -> posted.increment();
            case REJECTED_FUNDS -> rejectedFunds.increment();
            case REJECTED_INVALID -> rejectedInvalid.increment();
            case REJECTED_CONTENTION -> rejectedContention.increment();
            default -> throw new IllegalStateException("a ledger never answers " + outcome);
        }
    }

    private void tellListener(Transfer transfer, Outcome outcome) {
        try {
            listener.onOutcome(transfer, outcome);
        } catch (RuntimeException e) {
            listenerErrors.increment(); // a listener bug must not kill a worker
        }
    }

    private void publish(Posting posting) {
        try {
            publisher.publish(posting);
        } catch (RuntimeException e) {
            publishErrors.increment(); // a publisher bug must not kill a worker either
        }
    }

    private void answerQueueFull(Job job) {
        queueFull.increment();
        idempotency.release(job.transfer().id(), job.claim());
        job.claim().outcome().complete(Outcome.QUEUE_FULL);
    }

    /** Waits until every accepted transfer has been processed. False if {@code timeout} passed first. */
    public boolean drain(Duration timeout) {
        try {
            return inFlight.awaitIdle(timeout);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /**
     * Refuses new work, drains for up to {@code drainTimeout}, answers anything still queued with
     * {@code QUEUE_FULL} (so no caller waits forever), then waits for running jobs. Idempotent.
     */
    @Override
    public void close() {
        closing.writeLock().lock();
        try {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
        } finally {
            closing.writeLock().unlock();
        }
        drain(config.drainTimeout());
        pool.stopTaking();
        for (Job leftover : intake.removeAll()) {
            answerQueueFull(leftover);
            inFlight.decrement();
        }
        try {
            pool.awaitStopped();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public boolean isClosed() {
        return closed.get();
    }

    /** One account's balance, or empty if unknown (REQ-API-004). */
    public Optional<Money> balance(AccountId id) {
        return ledger.balance(id);
    }

    public Map<AccountId, Money> snapshot() {
        return ledger.snapshot();
    }

    public List<Posting> postings() {
        return ledger.postings();
    }

    public EngineMetrics metrics() {
        return new EngineMetrics(
                submitted.sum(),
                posted.sum(),
                rejectedFunds.sum(),
                rejectedInvalid.sum(),
                queueFull.sum(),
                rejectedContention.sum(),
                idempotency.duplicates(),
                failed.sum(),
                listenerErrors.sum(),
                publishErrors.sum(),
                intake.depth());
    }
}
