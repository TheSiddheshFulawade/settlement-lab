package com.siddheshfulawade.settlementlab.pipeline;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * The bounded queue between callers and workers. Bounded on purpose: when workers fall behind,
 * callers are told {@code QUEUE_FULL} after a short wait instead of memory growing without limit
 * (back-pressure, REQ-LAB-009).
 */
final class BoundedIntake {

    private final BlockingQueue<Job> queue;

    BoundedIntake(int capacity) {
        this.queue = new ArrayBlockingQueue<>(capacity);
    }

    /** Waits at most {@code timeout} for space. False means full; an interrupt also counts as full. */
    boolean offer(Job job, Duration timeout) {
        try {
            return queue.offer(job, timeout.toNanos(), TimeUnit.NANOSECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /** The next job, or null if none arrives within {@code timeout}. */
    Job poll(Duration timeout) throws InterruptedException {
        return queue.poll(timeout.toNanos(), TimeUnit.NANOSECONDS);
    }

    /** Removes and returns everything still waiting. Each job goes to exactly one caller of poll or this. */
    List<Job> removeAll() {
        List<Job> left = new ArrayList<>();
        queue.drainTo(left);
        return left;
    }

    int depth() {
        return queue.size();
    }
}
