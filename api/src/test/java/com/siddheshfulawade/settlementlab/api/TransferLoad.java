package com.siddheshfulawade.settlementlab.api;

import com.jayway.jsonpath.JsonPath;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.SplittableRandom;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Sends a {@link TransferPlan} over HTTP from many virtual-thread clients and records every answer.
 * Some transfers are sent by several clients at the same moment, and a sample is sent again after the
 * first phase (a late duplicate). A 503 is retried with the same id after its Retry-After, a bounded
 * number of times.
 */
final class TransferLoad {

    enum Phase {
        FIRST,
        CONCURRENT_COPY,
        REPLAY
    }

    /** One HTTP answer. {@code outcome} is null when the body was not a transfer outcome (a problem detail). */
    record Answer(String id, Phase phase, int status, String outcome) {
        /** The transfer was decided: posted or rejected, never "try again". */
        boolean decided() {
            return status != 503
                    && outcome != null
                    && !outcome.equals("QUEUE_FULL")
                    && !outcome.equals("REJECTED_CONTENTION");
        }
    }

    static final int MAX_ATTEMPTS = 5;
    /** One deadline for the whole load (both phases), not one per thread. */
    private static final Duration LOAD_DEADLINE = Duration.ofSeconds(120);

    private static final String PATH = "/api/v1/transfers";

    private final LoadHttp http;
    private final int clients;
    private final Duration deadline;
    private final Queue<Answer> answers = new ConcurrentLinkedQueue<>();
    /** Set when the load gives up: no client starts another request or retry after it. */
    private volatile boolean stopped;

    TransferLoad(LoadHttp http, int clients) {
        this(http, clients, LOAD_DEADLINE);
    }

    TransferLoad(LoadHttp http, int clients, Duration deadline) {
        this.http = http;
        this.clients = clients;
        this.deadline = deadline;
    }

    /** Runs the whole load: every transfer (with its concurrent copies), then late repeats of a sample. */
    List<Answer> run(List<TransferPlan.Op> ops, long seed) {
        long deadlineNanos = System.nanoTime() + deadline.toNanos();
        runInParallel(ops.stream().<Runnable>map(op -> () -> sendWithCopies(op)).toList(), deadlineNanos);

        SplittableRandom random = new SplittableRandom(seed + 1);
        List<Runnable> replays = new ArrayList<>();
        for (TransferPlan.Op op : ops) {
            if (random.nextInt(100) < 15) {
                replays.add(() -> sendWithRetries(op, Phase.REPLAY));
            }
        }
        runInParallel(replays, deadlineNanos);
        return List.copyOf(answers);
    }

    private void sendWithCopies(TransferPlan.Op op) {
        if (op.copies() == 1) {
            sendWithRetries(op, Phase.FIRST);
            return;
        }
        CountDownLatch together = new CountDownLatch(1);
        List<Thread> copies = new ArrayList<>();
        for (int c = 0; c < op.copies(); c++) {
            Phase phase = c == 0 ? Phase.FIRST : Phase.CONCURRENT_COPY;
            copies.add(Thread.ofVirtual().start(() -> {
                awaitQuietly(together);
                sendWithRetries(op, phase);
            }));
        }
        together.countDown();
        copies.forEach(TransferLoad::joinQuietly);
    }

    /** Invariant: a 503 stored nothing, so the same id is sent again after Retry-After (at most MAX_ATTEMPTS). */
    private void sendWithRetries(TransferPlan.Op op, Phase phase) {
        for (int attempt = 1; attempt <= MAX_ATTEMPTS && !stopped; attempt++) {
            LoadHttp.Response response = http.post(PATH, op.json());
            Answer answer = new Answer(op.id(), phase, response.status(), outcomeOf(response.body()));
            answers.add(answer);
            if (response.status() != 503 || attempt == MAX_ATTEMPTS) {
                return;
            }
            try {
                Thread.sleep(Duration.ofSeconds(response.retryAfterSeconds())); // the server asked for this wait
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    /**
     * At most {@code clients} tasks at a time, all released together. Invariant: the shared deadline ends the
     * wait for everyone; then {@code stopped} is set and the clients are interrupted, so none keeps sending.
     */
    private void runInParallel(List<Runnable> tasks, long deadlineNanos) {
        AtomicInteger next = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        List<Thread> threads = new ArrayList<>();
        for (int c = 0; c < clients; c++) {
            threads.add(Thread.ofVirtual().start(() -> {
                awaitQuietly(start);
                int i;
                while (!stopped && (i = next.getAndIncrement()) < tasks.size()) {
                    tasks.get(i).run();
                }
            }));
        }
        start.countDown();
        for (Thread thread : threads) {
            try {
                if (!thread.join(Duration.ofNanos(Math.max(1, deadlineNanos - System.nanoTime())))) {
                    stopped = true;
                    threads.forEach(Thread::interrupt);
                    throw new IllegalStateException("the load did not finish within " + deadline);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted while waiting for the load", e);
            }
        }
    }

    /** The "outcome" field of a transfer answer, or null for anything else. */
    static String outcomeOf(String body) {
        try {
            return JsonPath.read(body, "$.outcome");
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void joinQuietly(Thread thread) {
        try {
            thread.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
