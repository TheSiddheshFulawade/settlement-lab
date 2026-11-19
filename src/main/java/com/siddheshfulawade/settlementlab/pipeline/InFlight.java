package com.siddheshfulawade.settlementlab.pipeline;

import java.time.Duration;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Counts jobs accepted but not yet finished, so {@code drain} can wait for zero without polling.
 * A lock and condition rather than {@code synchronized}/{@code wait}: on JDK 21 a virtual thread
 * blocked inside {@code synchronized} pins its carrier thread.
 */
final class InFlight {

    private final ReentrantLock lock = new ReentrantLock();
    private final Condition idle = lock.newCondition();
    private long count; // guarded by lock

    void increment() {
        lock.lock();
        try {
            count++;
        } finally {
            lock.unlock();
        }
    }

    void decrement() {
        lock.lock();
        try {
            if (--count == 0) {
                idle.signalAll();
            }
        } finally {
            lock.unlock();
        }
    }

    /** True once nothing is in flight; false if {@code timeout} passed first. */
    boolean awaitIdle(Duration timeout) throws InterruptedException {
        long remaining = timeout.toNanos();
        lock.lock();
        try {
            while (count > 0) {
                if (remaining <= 0) {
                    return false;
                }
                remaining = idle.awaitNanos(remaining);
            }
            return true;
        } finally {
            lock.unlock();
        }
    }
}
