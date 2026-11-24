package com.siddheshfulawade.settlementlab.api;

import com.siddheshfulawade.settlementlab.pipeline.SettlementListener;
import com.siddheshfulawade.settlementlab.runner.Workload;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The run registry: starts runs under the limits, finds them by id, and forgets them on time
 * (REQ-RUN-001, 005, 007, 012).
 *
 * <p>Invariants, in the order a run meets them:
 *
 * <ul>
 *   <li><b>One slot, taken once and given back once.</b> {@link RunLimits#tryStart} takes the active slot
 *       and the window entries atomically. The {@link Admission} is handed to the run, and every end of the
 *       run (finish, expiry, failure, shutdown) calls its idempotent release, so the slot cannot leak and
 *       cannot be freed twice. A start that fails before the run exists releases it here.
 *   <li><b>Expiry before every runs request.</b> A run that passed 60 s without a report (for example the
 *       instance lost its CPU, so its own deadline never fired) is ended by the next {@code start} or
 *       {@code find}, which frees its slot and answers 404 for it. A finished run is forgotten once its
 *       retention has passed. Both are decided on the injected {@link Clock}.
 *   <li><b>Nothing outlives {@link #close()}.</b> It ends every run, waits (bounded) for each driver to close
 *       its engine, and stops the sampler. A start racing with close either sees {@code closed} or is ended by
 *       close's sweep; both leave nothing running.
 * </ul>
 */
final class RunService implements AutoCloseable {

    /** How a run's engine stack is built; tests replace it to make a start fail. */
    @FunctionalInterface
    interface RuntimeFactory {
        SettlementRuntime start(SettlementProperties properties, SettlementListener listener);
    }

    /** A run without a report after this long is lost (REQ-RUN-005). */
    private static final Duration REPORT_LIMIT = Duration.ofSeconds(60);

    private static final int WORKERS = 8;
    private static final int QUEUE_CAPACITY = 1_024;
    private static final Duration OFFER_TIMEOUT = Duration.ofMillis(100);
    /** The run drains for itself (bounded, on the clock); this is only the final wait when closing. */
    private static final Duration CLOSE_DRAIN = Duration.ofSeconds(1);

    private static final Duration SHUTDOWN_WAIT = Duration.ofSeconds(3);

    /** How often an idle service forgets visitors whose hour has passed (REQ-RUN-006). */
    private static final Duration FORGET_INTERVAL = Duration.ofSeconds(10);

    private static final Pattern RUN_ID = Pattern.compile("[A-Za-z0-9-]{1,64}");
    private static final Logger LOG = LoggerFactory.getLogger(RunService.class);

    private final RunsProperties properties;
    private final Clock clock;
    private final RuntimeFactory factory;
    private final RunLimits limits;
    private final Map<String, Run> runs = new ConcurrentHashMap<>();
    private final ScheduledThreadPoolExecutor sampler;
    private final SecureRandom random = new SecureRandom();
    private final AtomicBoolean closed = new AtomicBoolean();

    RunService(RunsProperties properties, Clock clock) {
        this(properties, clock, defaultFactory(properties));
    }

    RunService(RunsProperties properties, Clock clock, RuntimeFactory factory) {
        this(properties, clock, factory, FORGET_INTERVAL);
    }

    RunService(RunsProperties properties, Clock clock, RuntimeFactory factory, Duration forgetInterval) {
        this.properties = properties;
        this.clock = clock;
        this.factory = factory;
        this.limits =
                new RunLimits(clock, properties.perIpPerHour(), properties.globalPerHour(), properties.maxActive());
        // One thread samples every active run. It only reads counters and offers into bounded buffers, so
        // a slow stream client can never delay it (see RunSubscriber). Created on first use.
        this.sampler = new ScheduledThreadPoolExecutor(1, task -> {
            Thread thread = new Thread(task, "run-sampler");
            thread.setDaemon(true);
            return thread;
        });
        this.sampler.setRemoveOnCancelPolicy(true);
        this.sampler.scheduleWithFixedDelay(
                limits::forgetOldStarts, forgetInterval.toNanos(), forgetInterval.toNanos(), TimeUnit.NANOSECONDS);
    }

    /** Runs use the fault profile with this service's outage window. */
    private static RuntimeFactory defaultFactory(RunsProperties properties) {
        return (settings, listener) ->
                SettlementRuntime.start(settings, listener, properties.faultProfile(settings.faults()));
    }

    /**
     * Starts a run for the visitor {@code clientKey}.
     *
     * @throws RunRefusedException if a limit refuses it (nothing is taken)
     * @throws IllegalStateException after {@link #close()}
     */
    Run start(RunRequest request, String clientKey) {
        if (closed.get()) {
            throw new ServiceClosedException();
        }
        expireAndForget();
        Admission admission = limits.tryStart(clientKey);
        if (!admission.admitted()) {
            throw new RunRefusedException(admission.refusal().orElseThrow(), admission.retryAfterSeconds());
        }
        SettlementRuntime runtime = null;
        Run run = null;
        ScheduledFuture<?> sampling = null;
        try {
            runtime = factory.start(engineSettings(request), SettlementListener.NONE);
            Instant startedAt = clock.instant();
            RunProgress progress = new RunProgress(runtime, clock, startedAt, request.transfers());
            run = new Run(newRunId(), request, startedAt, properties, admission, progress::metrics);
            RunDriver driver = new RunDriver(run, request, properties, clock, runtime, progress);
            Thread thread = Thread.ofVirtual().name("run-driver-" + run.id()).unstarted(driver);
            runs.put(run.id(), run);
            long interval = properties.sampleInterval().toNanos();
            sampling = sampler.scheduleWithFixedDelay(sampleSafely(run), interval, interval, TimeUnit.NANOSECONDS);
            run.attach(thread, sampling);
            thread.start(); // from here the driver owns the runtime and releases the slot
            if (closed.get()) {
                run.end(clock.instant()); // close() may have swept before we registered: end it ourselves
            }
            return run;
        } catch (RuntimeException | Error failure) {
            // The driver never started, so nothing else will release or close: do it here, once.
            if (sampling != null) {
                sampling.cancel(false);
            }
            if (run != null) {
                runs.remove(run.id());
            }
            if (runtime != null) {
                closeQuietly(runtime);
            }
            admission.release();
            throw failure;
        }
    }

    /**
     * The run with this id, if it exists and has not expired. Expires lost runs and forgets old reports first.
     * An id that is not {@code [A-Za-z0-9-]{1,64}} is simply not found.
     */
    Optional<Run> find(String runId) {
        expireAndForget();
        if (runId == null || !RUN_ID.matcher(runId).matches()) {
            return Optional.empty();
        }
        return Optional.ofNullable(runs.get(runId));
    }

    /** Visitors the limiter still remembers. */
    int trackedVisitors() {
        return limits.trackedClients();
    }

    int storedRuns() {
        return runs.size();
    }

    /** Ends runs that passed 60 s with no report and drops runs that ended or whose retention passed. */
    private void expireAndForget() {
        Instant now = clock.instant();
        for (Run run : runs.values()) {
            if (run.isRunning()) {
                // end() is a no-op if the run finished in the meantime; then it stays (and is DONE).
                if (!now.isBefore(run.startedAt().plus(REPORT_LIMIT)) && run.end(now)) {
                    runs.remove(run.id(), run);
                }
            } else if (run.isEnded()) {
                runs.remove(run.id(), run);
            } else if (!now.isBefore(run.endedAt().plus(properties.retention()))) {
                runs.remove(run.id(), run); // DONE and past retention
            }
        }
    }

    private Runnable sampleSafely(Run run) {
        return () -> {
            try {
                run.sample();
            } catch (RuntimeException failure) {
                // keep sampling: one bad tick must not silence every later one
                LOG.warn(
                        "run {}: sampling failed: {}",
                        run.id(),
                        failure.getClass().getSimpleName());
            }
        };
    }

    private SettlementProperties engineSettings(RunRequest request) {
        return new SettlementProperties(
                request.ledger(),
                request.threads(),
                RunDriver.ACCOUNTS,
                WORKERS,
                QUEUE_CAPACITY,
                Workload.OPENING_BALANCE,
                OFFER_TIMEOUT,
                CLOSE_DRAIN,
                request.faults(),
                RunDriver.SEED);
    }

    private String newRunId() {
        while (true) {
            byte[] bytes = new byte[8];
            random.nextBytes(bytes); // unguessable: an id is the only thing protecting a run's stream
            String id = "r-" + HexFormat.of().formatHex(bytes);
            if (!runs.containsKey(id)) {
                return id;
            }
        }
    }

    private static void closeQuietly(SettlementRuntime runtime) {
        try {
            runtime.close();
        } catch (RuntimeException failure) {
            LOG.warn("closing an engine failed: {}", failure.getClass().getSimpleName());
        }
    }

    /** Ends every run, waits (bounded) for the engines to close, stops the sampler. Idempotent. */
    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        List<Run> all = new ArrayList<>(runs.values());
        Instant now = clock.instant();
        for (Run run : all) {
            run.end(now); // closes streams, tells the driver to stop; a no-op for DONE runs
        }
        long deadline = System.nanoTime() + SHUTDOWN_WAIT.toNanos();
        for (Run run : all) {
            run.awaitDriver(deadline - System.nanoTime()); // each driver closes its engine and outbox
        }
        runs.clear();
        sampler.shutdownNow();
    }
}
