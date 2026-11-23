package com.siddheshfulawade.settlementlab.api;

import java.util.ArrayDeque;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;

/**
 * One event-stream client of one run: a small bounded buffer and one virtual thread that writes from it.
 *
 * <p>Invariant 1 (a subscriber never slows the run): the producers (the sampler thread and the driver)
 * only call {@link #offerMetrics} and {@link #offerReport}. Those take this subscriber's lock for a few
 * instructions and never wait for the client. The slow part, {@code sink.send}, runs on this
 * subscriber's own thread, outside the lock.
 *
 * <p>Invariant 2 (the report is never dropped): when the buffer is full, the <em>oldest</em> entry is
 * dropped to make room. Only {@code metrics} can be dropped that way, because the report is offered
 * once, last, after which no metrics are offered ({@link Run} guarantees that order), so the report is
 * always the newest entry and is never the one dropped.
 *
 * <p>Invariant 3 (the sink is closed exactly once, never by a thread that could be blocked): {@link
 * #cancel()} wins a flag under the lock and then closes the sink on a fresh virtual thread, because a
 * close may block behind a write that a stalled client never finishes.
 */
final class RunSubscriber {

    private final RunSink sink;
    private final int capacity;
    private final Consumer<RunSubscriber> onGone;
    private final Thread sender;

    private final ReentrantLock lock = new ReentrantLock();
    private final Condition hasEvent = lock.newCondition();
    private final ArrayDeque<RunEvent> buffer = new ArrayDeque<>(); // guarded by lock
    private boolean open = true; // guarded by lock

    /** Created unstarted: the run registers it before {@link #start()}, so a cancel can never be missed. */
    RunSubscriber(RunSink sink, int capacity, Consumer<RunSubscriber> onGone) {
        this.sink = sink;
        this.capacity = capacity;
        this.onGone = onGone;
        this.sender = Thread.ofVirtual().name("run-sender").unstarted(this::deliver);
    }

    void start() {
        sender.start();
    }

    /** Queues a metrics event; if the buffer is full the oldest event is dropped. Never blocks on the client. */
    void offerMetrics(RunEvent metrics) {
        offer(metrics);
    }

    /** Queues the final report, dropping the oldest metrics if there is no room. Never blocks on the client. */
    void offerReport(RunEvent report) {
        offer(report);
    }

    private void offer(RunEvent event) {
        lock.lock();
        try {
            if (!open) {
                return;
            }
            while (buffer.size() >= capacity) {
                buffer.pollFirst();
            }
            buffer.addLast(event);
            hasEvent.signal();
        } finally {
            lock.unlock();
        }
    }

    /**
     * Ends this subscription: frees its place, wakes the sender, closes the sink. Safe from any thread,
     * any number of times.
     *
     * @return the thread closing the sink, or null if an earlier call already did
     */
    Thread cancel() {
        lock.lock();
        try {
            if (!open) {
                return null;
            }
            open = false;
            buffer.clear();
            hasEvent.signalAll();
        } finally {
            lock.unlock();
        }
        sender.interrupt(); // a no-op if not started; otherwise ends a wait or a stuck write
        onGone.accept(this); // outside our lock: the run takes its own lock here
        return Thread.startVirtualThread(this::closeSink);
    }

    private void closeSink() {
        try {
            sink.close();
        } catch (RuntimeException ignored) {
            // the stream is already gone; nothing else to do for this subscriber
        }
    }

    private void deliver() {
        try {
            while (true) {
                RunEvent next = takeNext();
                if (next == null) {
                    return; // cancelled
                }
                sink.send(next); // may block for as long as the client likes: this thread is only ours
                if (next.isReport()) {
                    cancel(); // the stream is complete: close it and free the place
                    return;
                }
            }
        } catch (InterruptedException cancelled) {
            Thread.currentThread().interrupt();
        } catch (Exception writeFailed) {
            cancel(); // a gone client frees its place on its first failed write
        }
    }

    /** The next event, waiting for one; null once cancelled. */
    private RunEvent takeNext() throws InterruptedException {
        lock.lock();
        try {
            while (open && buffer.isEmpty()) {
                hasEvent.await();
            }
            return open ? buffer.pollFirst() : null;
        } finally {
            lock.unlock();
        }
    }
}
