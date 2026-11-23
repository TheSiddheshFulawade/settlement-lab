package com.siddheshfulawade.settlementlab.api;

import com.siddheshfulawade.settlementlab.domain.Outcome;
import com.siddheshfulawade.settlementlab.domain.Transfer;
import com.siddheshfulawade.settlementlab.pipeline.SettlementListener;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.LongAdder;

/**
 * Fans each processed transfer out to stream subscribers (REQ-API-005), without ever letting a
 * client slow the engine down.
 *
 * <p>The engine calls {@link #onOutcome} on a worker thread. For each subscriber it only does a
 * non-blocking {@code offer} into that subscriber's bounded buffer; a full buffer means the client
 * is too slow, and it is dropped (the stream ends, the client may reconnect). One virtual thread per
 * subscriber takes from the buffer and writes to the connection; if a write fails, that subscriber is
 * removed. So a slow or vanished client costs at most its own buffer, never a worker's time.
 */
public final class EventHub implements SettlementListener, AutoCloseable {

    /** Where one subscriber's events go: in the app, an SSE connection. */
    public interface Sink {
        void send(OutcomeEvent event) throws Exception;

        void close();
    }

    private static final Duration POLL = Duration.ofMillis(100);

    private final class Subscription {
        final Sink sink;
        final BlockingQueue<OutcomeEvent> buffer;
        final AtomicBoolean open = new AtomicBoolean(true);
        /** Created unstarted, so it is set before anyone else can see this subscription. */
        final Thread sender;

        Subscription(Sink sink) {
            this.sink = sink;
            this.buffer = new ArrayBlockingQueue<>(bufferSize);
            this.sender = Thread.ofVirtual().name("sse-sender").unstarted(this::deliver);
        }

        void deliver() {
            try {
                while (open.get()) {
                    OutcomeEvent event = buffer.poll(POLL.toNanos(), TimeUnit.NANOSECONDS);
                    if (event != null) {
                        sink.send(event);
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception connectionFailed) {
                drop(this);
            }
        }
    }

    private final int bufferSize;
    private final Set<Subscription> subscriptions = ConcurrentHashMap.newKeySet();
    private final LongAdder dropped = new LongAdder();
    private volatile boolean closed;

    public EventHub(int bufferSize) {
        if (bufferSize < 1) {
            throw new IllegalArgumentException("bufferSize must be at least 1");
        }
        this.bufferSize = bufferSize;
    }

    /** Starts delivering to {@code sink}; returns a handle to cancel it with. */
    public Runnable subscribe(Sink sink) {
        if (closed) {
            closeQuietly(sink); // runs on the caller's thread, never the engine's
            return () -> {};
        }
        Subscription subscription = new Subscription(sink);
        subscriptions.add(subscription);
        // Invariant: no subscription outlives close(). close() sets 'closed' and then iterates; we add
        // and then re-check 'closed'. Either the iteration sees our add, or we see 'closed' here. Both
        // may happen; drop() is idempotent (compareAndSet), so the sink is still closed exactly once.
        if (closed) {
            drop(subscription);
        } else {
            // Start only after the checks. A cancel before this point is safe: drop() interrupts an
            // unstarted thread (a no-op) and deliver() checks 'open' first, so it exits at once.
            subscription.sender.start();
        }
        return () -> drop(subscription);
    }

    @Override
    public void onOutcome(Transfer transfer, Outcome outcome) {
        if (subscriptions.isEmpty()) {
            return;
        }
        OutcomeEvent event = OutcomeEvent.of(transfer, outcome);
        for (Subscription subscription : subscriptions) {
            if (!subscription.buffer.offer(event)) { // never blocks: full means too slow
                dropped.increment();
                drop(subscription);
            }
        }
    }

    /**
     * Ends one subscription. Invariant: the engine thread only ever does a non-blocking offer plus
     * this method, so it must never wait. The winner of the compareAndSet runs the sink's close once,
     * on its own virtual thread: close may block or throw, and the sender may be stuck in a write.
     */
    private void drop(Subscription subscription) {
        if (subscription.open.compareAndSet(true, false)) {
            subscriptions.remove(subscription);
            subscription.sender.interrupt();
            Thread.startVirtualThread(() -> closeQuietly(subscription.sink));
        }
    }

    /** A failing close (e.g. the emitter already completed) must not affect anyone else. */
    private static void closeQuietly(Sink sink) {
        try {
            sink.close();
        } catch (RuntimeException ignored) {
            // nothing to do: the subscription is already gone
        }
    }

    public int subscribers() {
        return subscriptions.size();
    }

    /** Subscribers dropped for being too slow. */
    public long dropped() {
        return dropped.sum();
    }

    @Override
    public void close() {
        closed = true; // before iterating: see the re-check in subscribe()
        subscriptions.forEach(this::drop);
    }
}
