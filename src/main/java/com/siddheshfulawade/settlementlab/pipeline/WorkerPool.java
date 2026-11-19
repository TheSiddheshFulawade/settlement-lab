package com.siddheshfulawade.settlementlab.pipeline;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Takes jobs from the intake and runs them, at most {@code workers} at a time, in either
 * {@link ThreadMode} (REQ-LAB-010).
 *
 * <ul>
 *   <li>{@code PLATFORM}: N platform threads, each looping "take a job, run it".
 *   <li>{@code VIRTUAL}: one dispatcher takes a permit from a {@code Semaphore(N)}, takes a job, and
 *       starts a new virtual thread for it, which returns the permit when done.
 * </ul>
 *
 * The same N in both modes keeps the comparison fair: we measure the threading model, not a
 * different amount of parallelism. Loops poll with a short timeout so {@link #stopTaking()} is seen
 * promptly without interrupting a job half-way.
 */
final class WorkerPool {

    private static final Duration POLL = Duration.ofMillis(20);

    private final ThreadMode mode;
    private final int workers;
    private final BoundedIntake intake;
    private final Consumer<Job> processor;
    private final List<Thread> loops = new ArrayList<>();
    private final Semaphore permits;
    private final ExecutorService virtualThreads;
    private volatile boolean taking = true;

    WorkerPool(ThreadMode mode, int workers, BoundedIntake intake, Consumer<Job> processor) {
        this.mode = mode;
        this.workers = workers;
        this.intake = intake;
        this.processor = processor;
        this.permits = new Semaphore(workers);
        this.virtualThreads = mode == ThreadMode.VIRTUAL ? Executors.newVirtualThreadPerTaskExecutor() : null;
    }

    void start() {
        if (mode == ThreadMode.PLATFORM) {
            for (int i = 0; i < workers; i++) {
                loops.add(
                        Thread.ofPlatform().name("settle-worker-" + i).daemon().start(this::workerLoop));
            }
        } else {
            loops.add(Thread.ofPlatform().name("settle-dispatcher").daemon().start(this::dispatcherLoop));
        }
    }

    private void workerLoop() {
        try {
            while (taking) {
                Job job = intake.poll(POLL);
                if (job != null) {
                    processor.accept(job);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void dispatcherLoop() {
        try {
            while (taking) {
                if (!permits.tryAcquire(POLL.toNanos(), TimeUnit.NANOSECONDS)) {
                    continue; // all N busy
                }
                Job job = intake.poll(POLL);
                if (job == null) {
                    permits.release();
                    continue;
                }
                virtualThreads.execute(() -> {
                    try {
                        processor.accept(job);
                    } finally {
                        permits.release();
                    }
                });
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Stops taking new jobs. Jobs already taken still finish. */
    void stopTaking() {
        taking = false;
    }

    /** Waits for the loops and any running job to finish. */
    void awaitStopped() throws InterruptedException {
        for (Thread loop : loops) {
            loop.join();
        }
        if (virtualThreads != null) {
            virtualThreads.shutdown();
            while (!virtualThreads.awaitTermination(1, TimeUnit.SECONDS)) {
                // keep waiting: a running job always finishes, it is never abandoned half-way
            }
        }
    }
}
