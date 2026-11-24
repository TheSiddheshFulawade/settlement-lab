package com.siddheshfulawade.settlementlab.api;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/**
 * One run as the outside world sees it: an id, a status, a report once there is one, and event
 * subscribers. The work itself is done by {@link RunDriver}; this class owns the life cycle.
 *
 * <p>The life cycle is a one-way state machine: {@code RUNNING} to {@code DONE} (a report exists) or
 * {@code RUNNING} to {@code ENDED} (expired, or the application stopped; no report). Both moves happen
 * under {@link #lock}, and only from {@code RUNNING}, so exactly one of them wins:
 *
 * <ul>
 *   <li><b>finish vs expire:</b> if the driver finishes first the run is DONE and an expiry request finds
 *       nothing to do; if expiry wins, the driver's later {@link #finish} returns false and its report is
 *       discarded. No run is ever both reported and expired, and no stream gets a report after its close.
 *   <li><b>subscribe vs finish:</b> {@link #subscribe} reads the state and registers the subscriber under
 *       the same lock that {@link #finish} uses to set DONE and hand the report to every registered
 *       subscriber. A subscriber is therefore either registered before the report (and is handed it) or
 *       sees DONE (and is handed it at once). It cannot fall between.
 *   <li><b>sample vs finish:</b> a metrics event is offered only while RUNNING, under the same lock, so
 *       no metrics event is ever queued after the report.
 * </ul>
 *
 * Nothing under the lock waits for a client or for I/O: subscribers only take a short buffer lock.
 */
final class Run {

    /** How long {@link #end()} waits for each stream to close before moving on. */
    private static final Duration CLOSE_WAIT = Duration.ofMillis(200);

    /** A run without a report this long after its 202 is lost (REQ-RUN-005); the one copy of the 60 s. */
    static final Duration REPORT_LIMIT = Duration.ofSeconds(60);

    private enum State {
        RUNNING,
        DONE,
        ENDED
    }

    private final String id;
    private final RunRequest settings;
    private final Instant startedAt;
    private final int maxSubscribers;
    private final int eventBuffer;
    private final Admission admission;

    private final ReentrantLock lock = new ReentrantLock();
    private State state = State.RUNNING; // guarded by lock
    private final Set<RunSubscriber> subscribers = new HashSet<>(); // guarded by lock

    private volatile RunReport report;
    private volatile Instant endedAt; // when it became DONE or ENDED
    private volatile boolean stopRequested;
    /**
     * Invariant (nothing leaks past RUNNING): a run that is DONE or ENDED reaches neither its metrics source nor
     * its driver, and so not the engine stack ({@link SettlementRuntime}) they lead to. {@code metricsSource} is
     * dropped when the run leaves RUNNING; {@code driver} is dropped by the driver itself as its very last step
     * ({@link #driverDone}), so {@link #awaitDriver} can still wait for a live one. Both are read once into a local
     * before use, because the other side may null them at any moment.
     */
    private volatile Supplier<RunMetrics> metricsSource;

    private volatile Thread driver;
    private volatile ScheduledFuture<?> sampling;

    Run(
            String id,
            RunRequest settings,
            Instant startedAt,
            RunsProperties properties,
            Admission admission,
            Supplier<RunMetrics> metricsSource) {
        this.id = id;
        this.settings = settings;
        this.startedAt = startedAt;
        this.maxSubscribers = properties.subscribersPerRun();
        this.eventBuffer = properties.eventBuffer();
        this.admission = admission;
        this.metricsSource = metricsSource;
    }

    String id() {
        return id;
    }

    RunRequest settings() {
        return settings;
    }

    Instant startedAt() {
        return startedAt;
    }

    RunStatus status() {
        return report == null ? RunStatus.RUNNING : RunStatus.DONE;
    }

    Optional<RunReport> report() {
        return Optional.ofNullable(report);
    }

    /** True once the run was ended without a report, or the application asked it to stop. */
    boolean stopRequested() {
        return stopRequested;
    }

    /** When the run became DONE or ENDED; null while RUNNING. */
    Instant endedAt() {
        return endedAt;
    }

    boolean isRunning() {
        lock.lock();
        try {
            return state == State.RUNNING;
        } finally {
            lock.unlock();
        }
    }

    boolean isEnded() {
        lock.lock();
        try {
            return state == State.ENDED;
        } finally {
            lock.unlock();
        }
    }

    /** Called once by the service, after the run is registered and before it can be found. */
    void attach(Thread driver, ScheduledFuture<?> sampling) {
        this.driver = driver;
        this.sampling = sampling;
    }

    /**
     * Starts sending this run's events to {@code sink}: metrics while it runs, then the report, then the sink
     * is closed. For a finished run: the report only.
     *
     * @return a handle that cancels the subscription (the client went away)
     * @throws SubscriberLimitException if the run already serves its maximum number of subscribers
     */
    Runnable subscribe(RunSink sink) {
        RunSubscriber subscriber = new RunSubscriber(sink, eventBuffer, this::forget);
        boolean ended;
        lock.lock();
        try {
            if (subscribers.size() >= maxSubscribers) {
                throw new SubscriberLimitException();
            }
            subscribers.add(subscriber);
            if (state == State.DONE) {
                subscriber.offerReport(new RunEvent(RunEvent.REPORT, report));
            }
            ended = state == State.ENDED;
        } finally {
            lock.unlock();
        }
        if (ended) {
            subscriber.cancel(); // expired between the lookup and now: no report will ever come
        } else {
            subscriber.start();
        }
        return subscriber::cancel;
    }

    private void forget(RunSubscriber subscriber) {
        lock.lock();
        try {
            subscribers.remove(subscriber);
        } finally {
            lock.unlock();
        }
    }

    /** One sampler tick: offer the current metrics to every subscriber, if the run is still going. */
    void sample() {
        Supplier<RunMetrics> source = metricsSource;
        if (source == null || !isRunning()) {
            return;
        }
        RunMetrics metrics = source.get(); // read outside the lock: a few atomics and a sort
        RunEvent event = new RunEvent(RunEvent.METRICS, metrics);
        lock.lock();
        try {
            if (state != State.RUNNING) {
                return; // the report may already be queued: nothing may follow it
            }
            for (RunSubscriber subscriber : subscribers) {
                subscriber.offerMetrics(event);
            }
        } finally {
            lock.unlock();
        }
    }

    /**
     * The driver is done: publish the report if this run is still RUNNING.
     *
     * @return false if the run had already ended (expired or stopped); the report is then discarded
     */
    boolean finish(RunReport finalReport, Instant now) {
        // Invariant (REQ-RUN-005): no report is published 60 s or more after the start. A late one is treated as
        // an expired run, so the viewer never sees a report the lookup rule would already have refused.
        if (!now.isBefore(startedAt.plus(REPORT_LIMIT))) {
            end(now);
            return false;
        }
        lock.lock();
        try {
            if (state != State.RUNNING) {
                return false;
            }
            state = State.DONE;
            endedAt = now;
            report = finalReport; // volatile: GET sees DONE and the report together
            metricsSource = null;
            for (RunSubscriber subscriber : subscribers) {
                subscriber.offerReport(new RunEvent(RunEvent.REPORT, finalReport));
            }
        } finally {
            lock.unlock();
        }
        stopSampling();
        return true;
    }

    /**
     * Ends a run that has no report: it passed 60 s without one, the application is stopping, or it failed.
     * Streams are closed (each given up to {@link #CLOSE_WAIT} to do so), the sampler stops, the driver is
     * told to stop (not interrupted: an interrupt could land inside the engine's own close), and the active slot
     * is released at once. The driver closes the engine when it notices.
     * Does nothing if the run is already DONE or ENDED.
     *
     * @return true if this call ended the run; false if it was already DONE or ENDED
     */
    boolean end(Instant now) {
        List<RunSubscriber> toClose;
        lock.lock();
        try {
            if (state != State.RUNNING) {
                return false;
            }
            state = State.ENDED;
            endedAt = now;
            stopRequested = true;
            metricsSource = null;
            toClose = new ArrayList<>(subscribers);
        } finally {
            lock.unlock();
        }
        stopSampling();
        Thread worker = driver;
        if (worker != null) {
            LockSupport.unpark(worker); // wake it from its pacing wait; it sees stopRequested and cleans up
        }
        closeAll(toClose);
        admission.release();
        return true;
    }

    /** The driver calls this last, on every path: the slot is free once the engine is closed. */
    void releaseSlot() {
        admission.release(); // idempotent: end() may have released it already
    }

    /** The driver's last step, after its engine is closed and the slot released: forget the thread. */
    void driverDone() {
        driver = null;
        metricsSource = null;
    }

    private void stopSampling() {
        ScheduledFuture<?> task = sampling;
        if (task != null) {
            task.cancel(false);
        }
    }

    /** Closes every stream at once, then waits (bounded) so callers see them closed on return. */
    private static void closeAll(List<RunSubscriber> subscribers) {
        List<Thread> closers = new ArrayList<>();
        for (RunSubscriber subscriber : subscribers) {
            Thread closer = subscriber.cancel();
            if (closer != null) {
                closers.add(closer);
            }
        }
        long deadline = System.nanoTime() + CLOSE_WAIT.toNanos();
        for (Thread closer : closers) {
            try {
                long left = deadline - System.nanoTime();
                if (left > 0) {
                    closer.join(left / 1_000_000, (int) (left % 1_000_000));
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    /** Waits for the driver thread to finish its cleanup (service shutdown). */
    void awaitDriver(long timeoutNanos) {
        Thread worker = driver;
        if (worker == null) {
            return;
        }
        try {
            if (timeoutNanos > 0) {
                worker.join(TimeUnit.NANOSECONDS.toMillis(timeoutNanos) + 1);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
