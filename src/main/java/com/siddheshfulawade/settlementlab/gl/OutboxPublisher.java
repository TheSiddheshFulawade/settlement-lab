package com.siddheshfulawade.settlementlab.gl;

import com.siddheshfulawade.settlementlab.domain.Posting;
import com.siddheshfulawade.settlementlab.domain.PostingPublisher;
import java.time.Duration;
import java.util.concurrent.BlockingDeque;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Publishes postings to the general ledger, and never loses one (REQ-LAB-011, 012).
 *
 * <p>Each posting is tried once, directly. If that fails, or if the outbox already holds entries
 * (so it queues behind them), the posting goes into the <b>outbox</b>. A single <b>relay</b> thread
 * retries the outbox head, in order, every {@code retryInterval}, until it succeeds. Re-publishing is
 * safe because the general ledger is keyed by transfer id: a posting whose ack was lost is answered
 * {@code ALREADY_RECORDED}, not stored twice. So the effect is exactly once.
 *
 * <p>Retry with back-off and a circuit breaker around the direct call arrive in L-07.
 */
public final class OutboxPublisher implements PostingPublisher, AutoCloseable {

    private final GeneralLedgerPort port;
    private final Duration retryInterval;
    private final BlockingDeque<Posting> outbox = new LinkedBlockingDeque<>();
    private final Thread relay;
    private final CountDownLatch stop = new CountDownLatch(1);

    // pending = postings in the outbox or in the relay's hand; drain waits for zero.
    private final ReentrantLock lock = new ReentrantLock();
    private final Condition empty = lock.newCondition();
    private long pending; // guarded by lock

    private final LongAdder published = new LongAdder();
    private final LongAdder failedAttempts = new LongAdder();
    private final AtomicLong peak = new AtomicLong();

    public OutboxPublisher(GeneralLedgerPort port, Duration retryInterval) {
        this.port = port;
        this.retryInterval = retryInterval;
        this.relay = Thread.ofPlatform().name("gl-relay").daemon().start(this::relayLoop);
    }

    @Override
    public void publish(Posting posting) {
        // outboxSize() counts the entry the relay may hold in its hand, so nothing jumps the queue.
        if (outboxSize() == 0 && tryPublish(posting)) {
            return;
        }
        addPending();
        outbox.addLast(posting);
    }

    private boolean tryPublish(Posting posting) {
        try {
            port.publish(posting);
            published.increment();
            return true;
        } catch (GeneralLedgerException e) {
            failedAttempts.increment();
            return false;
        }
    }

    private void relayLoop() {
        try {
            while (stop.getCount() > 0) {
                Posting head = outbox.pollFirst(retryInterval.toNanos(), TimeUnit.NANOSECONDS);
                if (head == null) {
                    continue;
                }
                if (tryPublish(head)) {
                    removePending();
                } else {
                    outbox.offerFirst(head); // keep its place: the outbox is retried in order
                    stop.await(retryInterval.toNanos(), TimeUnit.NANOSECONDS); // back off, or wake to stop
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void addPending() {
        lock.lock();
        try {
            pending++;
            peak.accumulateAndGet(pending, Math::max);
        } finally {
            lock.unlock();
        }
    }

    private void removePending() {
        lock.lock();
        try {
            if (--pending == 0) {
                empty.signalAll();
            }
        } finally {
            lock.unlock();
        }
    }

    /** Waits until the outbox is empty. False if {@code timeout} passed first. */
    public boolean drain(Duration timeout) {
        long remaining = timeout.toNanos();
        lock.lock();
        try {
            while (pending > 0) {
                if (remaining <= 0) {
                    return false;
                }
                remaining = empty.awaitNanos(remaining);
            }
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } finally {
            lock.unlock();
        }
    }

    /** Stops the relay. Entries still in the outbox stay there (v1 keeps them in memory only). */
    @Override
    public void close() {
        stop.countDown();
        try {
            relay.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public long published() {
        return published.sum();
    }

    public long failedAttempts() {
        return failedAttempts.sum();
    }

    /** Postings waiting in the outbox now. */
    public long outboxSize() {
        lock.lock();
        try {
            return pending;
        } finally {
            lock.unlock();
        }
    }

    /** The most postings the outbox ever held at once. */
    public long outboxPeak() {
        return peak.get();
    }
}
