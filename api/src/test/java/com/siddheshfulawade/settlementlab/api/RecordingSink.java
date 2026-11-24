package com.siddheshfulawade.settlementlab.api;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** A run-event subscriber for tests: records what it gets, and can block or fail on purpose. */
final class RecordingSink implements RunSink {

    final List<RunEvent> events = new CopyOnWriteArrayList<>();
    final CountDownLatch closed = new CountDownLatch(1);
    final AtomicInteger closeCalls = new AtomicInteger();
    private final CountDownLatch blockFirstSendUntil;
    private final boolean failing;
    private final AtomicInteger sends = new AtomicInteger();

    private RecordingSink(CountDownLatch blockFirstSendUntil, boolean failing) {
        this.blockFirstSendUntil = blockFirstSendUntil;
        this.failing = failing;
    }

    static RecordingSink normal() {
        return new RecordingSink(null, false);
    }

    /** The first send blocks until {@code release} opens: a client that stopped reading. */
    static RecordingSink blockedUntil(CountDownLatch release) {
        return new RecordingSink(release, false);
    }

    /** Every send throws: a client that went away. */
    static RecordingSink failing() {
        return new RecordingSink(null, true);
    }

    @Override
    public void send(RunEvent event) throws Exception {
        if (failing) {
            throw new java.io.IOException("client gone");
        }
        if (sends.getAndIncrement() == 0 && blockFirstSendUntil != null) {
            blockFirstSendUntil.await(60, TimeUnit.SECONDS);
        }
        events.add(event);
    }

    @Override
    public void close() {
        closeCalls.incrementAndGet();
        closed.countDown();
    }

    boolean isClosed() {
        return closed.getCount() == 0;
    }

    long count(String name) {
        return events.stream().filter(e -> e.name().equals(name)).count();
    }

    List<RunEvent> named(String name) {
        return events.stream().filter(e -> e.name().equals(name)).toList();
    }
}
