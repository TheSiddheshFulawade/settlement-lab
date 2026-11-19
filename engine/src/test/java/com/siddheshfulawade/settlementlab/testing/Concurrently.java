package com.siddheshfulawade.settlementlab.testing;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.IntConsumer;

/**
 * Starts {@code threads} platform threads behind one gate so they really overlap, then waits for
 * them with a timeout. Threads are daemons: if the code under test deadlocks, the test fails on the
 * timeout and the stuck threads do not keep the JVM alive.
 */
public final class Concurrently {

    private Concurrently() {}

    public static void run(int threads, Duration timeout, IntConsumer body) throws InterruptedException {
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        Queue<Throwable> failures = new ConcurrentLinkedQueue<>();

        for (int i = 0; i < threads; i++) {
            int index = i;
            Thread.ofPlatform().daemon().name("stress-" + index).start(() -> {
                ready.countDown();
                try {
                    start.await();
                    body.accept(index);
                } catch (Throwable t) {
                    failures.add(t);
                } finally {
                    done.countDown();
                }
            });
        }

        ready.await();
        start.countDown();
        boolean finished = done.await(timeout.toMillis(), TimeUnit.MILLISECONDS);

        assertThat(finished)
                .as("all %d threads finish within %s; a hang here means a deadlock", threads, timeout)
                .isTrue();
        assertThat(failures).as("exceptions thrown by worker threads").isEmpty();
    }
}
