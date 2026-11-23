package com.siddheshfulawade.settlementlab.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;
import java.util.function.IntConsumer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Many threads start at once; the caps hold exactly (REQ-RUN-006, 007). Same assumed API as RunLimitsTest. */
class RunLimitsStressTest {

    private static final int THREADS = 64;

    private final MutableClock clock = new MutableClock(Instant.parse("2026-12-01T10:00:00Z"));

    /** Runs {@code body(threadIndex)} on THREADS threads released together; fails if any hangs or throws. */
    private static void inParallel(IntConsumer body) throws Exception {
        CountDownLatch ready = new CountDownLatch(THREADS);
        CountDownLatch go = new CountDownLatch(1);
        List<Thread> threads = new ArrayList<>();
        List<Throwable> failures = Collections.synchronizedList(new ArrayList<>());
        for (int i = 0; i < THREADS; i++) {
            int index = i;
            threads.add(Thread.ofPlatform().start(() -> {
                ready.countDown();
                try {
                    go.await();
                    body.accept(index);
                } catch (Throwable t) {
                    failures.add(t);
                }
            }));
        }
        assertThat(ready.await(30, TimeUnit.SECONDS)).isTrue();
        go.countDown();
        for (Thread thread : threads) {
            thread.join(30_000);
            assertThat(thread.isAlive()).as("thread finished").isFalse();
        }
        assertThat(failures).isEmpty();
    }

    @Test
    @DisplayName("REQ-RUN-007 concurrent starts never exceed 2 active runs, and no slot leaks")
    void neverMoreThanTwoActive() throws Exception {
        RunLimits limits = new RunLimits(clock, 1_000_000, 1_000_000, 2);
        AtomicInteger active = new AtomicInteger();
        AtomicInteger maxActive = new AtomicInteger();
        AtomicInteger admitted = new AtomicInteger();

        inParallel(thread -> {
            for (int round = 0; round < 200; round++) {
                Admission admission = limits.tryStart("198.51.100." + (thread % 200));
                if (admission.admitted()) {
                    admitted.incrementAndGet();
                    maxActive.accumulateAndGet(active.incrementAndGet(), Math::max);
                    LockSupport.parkNanos(50_000); // a little work while holding the slot
                    active.decrementAndGet();
                    admission.release();
                }
            }
        });

        assertThat(maxActive.get()).as("most runs ever active at once").isLessThanOrEqualTo(2);
        assertThat(admitted.get()).isPositive();
        assertThat(limits.tryStart("203.0.113.1").admitted())
                .as("first slot is free again")
                .isTrue();
        assertThat(limits.tryStart("203.0.113.2").admitted())
                .as("second slot is free again")
                .isTrue();
        assertThat(limits.tryStart("203.0.113.3").admitted())
                .as("but there are only two")
                .isFalse();
    }

    @Test
    @DisplayName("REQ-RUN-007 the global cap admits exactly 60 starts when 64 visitors race")
    void globalCapIsExact() throws Exception {
        RunLimits limits = new RunLimits(clock, 100, 60, 1_000);
        AtomicInteger admitted = new AtomicInteger();

        inParallel(thread -> {
            if (limits.tryStart("198.51.100." + thread).admitted()) {
                admitted.incrementAndGet();
            }
        });

        assertThat(admitted.get()).isEqualTo(60);
    }

    @Test
    @DisplayName("REQ-RUN-006 the per-IP cap admits exactly 5 starts when 64 requests from one IP race")
    void perIpCapIsExact() throws Exception {
        RunLimits limits = new RunLimits(clock, 5, 1_000, 1_000);
        AtomicInteger admitted = new AtomicInteger();

        inParallel(thread -> {
            if (limits.tryStart("198.51.100.1").admitted()) {
                admitted.incrementAndGet();
            }
        });

        assertThat(admitted.get()).isEqualTo(5);
    }
}
