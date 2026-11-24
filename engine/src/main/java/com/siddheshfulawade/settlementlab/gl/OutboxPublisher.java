package com.siddheshfulawade.settlementlab.gl;

import com.siddheshfulawade.settlementlab.domain.Posting;
import com.siddheshfulawade.settlementlab.domain.PostingPublisher;
import java.time.Duration;
import java.util.concurrent.BlockingDeque;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Publishes postings to the general ledger, and never loses one (REQ-LAB-011, 012).
 *
 * <p>{@code publish} returns at once: a bounded group of <b>sender</b> virtual threads (at most
 * {@code senders} at a time) tries each posting directly, through the retrying, circuit-breaking
 * port in front of the GL, so a slow or failing GL never holds up the engine's workers. Only if the
 * direct try fails does the posting go into the <b>outbox</b>. A single <b>relay</b> thread
 * retries the outbox head, in order, every {@code retryInterval}, until it succeeds. Re-publishing is
 * safe because the general ledger is keyed by transfer id: a posting whose ack was lost is answered
 * {@code ALREADY_RECORDED}, not stored twice. So the effect is exactly once.
 *
 */
public final class OutboxPublisher implements PostingPublisher, AutoCloseable {

    private final GeneralLedgerPort port;
    private final Duration retryInterval;
    private final BlockingDeque<Posting> outbox = new LinkedBlockingDeque<>();
    private final Thread relay;
    private final CountDownLatch stop = new CountDownLatch(1);
    private final ExecutorService senders = Executors.newVirtualThreadPerTaskExecutor();
    private final Semaphore senderPermits;

    // pending = postings being sent, in the outbox, or in the relay's hand; drain waits for zero.
    private final ReentrantLock lock = new ReentrantLock();
    private final Condition empty = lock.newCondition();
    private long pending; // guarded by lock

    private final LongAdder published = new LongAdder();
    private final LongAdder failedAttempts = new LongAdder();
    private final AtomicLong peak = new AtomicLong();
    private final AtomicLong waiting = new AtomicLong(); // postings in the outbox or the relay's hand

    /** 16 concurrent senders. */
    public OutboxPublisher(GeneralLedgerPort port, Duration retryInterval) {
        this(port, retryInterval, 16);
    }

    public OutboxPublisher(GeneralLedgerPort port, Duration retryInterval, int senders) {
        this.port = port;
        this.retryInterval = retryInterval;
        this.senderPermits = new Semaphore(senders);
        this.relay = Thread.ofPlatform().name("gl-relay").daemon().start(this::relayLoop);
    }

    @Override
    public void publish(Posting posting) {
        addPending();
        try {
            senders.execute(() -> send(posting));
        } catch (RejectedExecutionException closed) {
            toOutbox(posting); // after close: keep it, as close() promises
        }
    }

    private void send(Posting posting) {
        senderPermits.acquireUninterruptibly();
        try {
            // Always try directly first, even while older entries wait: GL entries are keyed by
            // transfer id, so order does not matter for correctness, and funnelling everything through
            // the single relay made it the bottleneck (measured in L-08: FLAKY never caught up). The
            // circuit breaker in front of the GL is what stops us hammering it during an outage.
            if (tryPublish(posting)) {
                removePending();
            } else {
                toOutbox(posting);
            }
        } finally {
            senderPermits.release();
        }
    }

    private void toOutbox(Posting posting) {
        peak.accumulateAndGet(waiting.incrementAndGet(), Math::max);
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
                    waiting.decrementAndGet();
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

    /**
     * Postings accepted by {@code publish()} that the general ledger has not yet accepted: waiting for a
     * sender, being sent, in the outbox, or in the relay's hand. Read-only.
     */
    public long pendingCount() {
        lock.lock();
        try {
            return pending;
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
        senders.shutdown();
        try {
            while (!senders.awaitTermination(1, TimeUnit.SECONDS)) {
                // sends in flight finish (or fail into the outbox); none is abandoned half-way
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
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

    /** Postings waiting in the outbox now (failed once, waiting for the relay). */
    public long outboxSize() {
        return waiting.get();
    }

    /** The most postings the outbox ever held at once. */
    public long outboxPeak() {
        return peak.get();
    }
}
