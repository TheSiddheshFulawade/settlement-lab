package com.siddheshfulawade.settlementlab.api;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The black-box HTTP load check (V2-05): talks to a base URL over {@code java.net.http} and knows nothing
 * about the server's classes. Part one sends a seeded transfer mix and checks the invariants from the
 * outside; part two starts one run and checks its event stream and report.
 */
final class HttpLoadCheck {

    /**
     * @param operations distinct transfer ids to send (about 1.4x that many requests with the copies and repeats)
     * @param clients virtual-thread clients sending at the same time
     * @param withTransfers send the transfer mix (needs the local endpoints; the public service has none)
     * @param withRun start one run
     */
    record Options(
            int operations,
            int clients,
            long seed,
            int runTransfers,
            String runLedger,
            String runThreads,
            boolean withTransfers,
            boolean withRun) {

        static Options defaults() {
            return new Options(1_500, 32, 20_261_124L, 2_000, "LOCKING", "VIRTUAL", true, true);
        }

        Options withRunSettings(String ledger, String threads) {
            return new Options(operations, clients, seed, runTransfers, ledger, threads, withTransfers, withRun);
        }
    }

    /** {@code transfers} is null when the transfer part did not run. */
    record Report(
            List<CheckResult> checks,
            long requests,
            Duration duration,
            Map<String, Long> statusCounts,
            Map<String, Long> routeCounts,
            TransferObservation transfers) {

        boolean passed() {
            // an empty list proves nothing, so it is not a pass
            return !checks.isEmpty() && checks.stream().allMatch(CheckResult::passed);
        }
    }

    private static final Duration GL_DRAIN_LIMIT = Duration.ofSeconds(20);

    private HttpLoadCheck() {}

    static Report run(String baseUrl, Options options) {
        LoadHttp http = new LoadHttp(baseUrl);
        long started = System.nanoTime();
        List<CheckResult> checks = new ArrayList<>();
        TransferObservation transfers = null;
        if (options.withTransfers()) {
            transfers = sendTransfers(http, options);
            checks.addAll(TransferChecks.evaluate(transfers));
        }
        if (options.withRun()) {
            RunLoad.RunObservation run =
                    RunLoad.run(http, options.runLedger(), options.runThreads(), "FLAKY", options.runTransfers());
            checks.addAll(RunChecks.evaluate(run));
        }
        return new Report(
                checks,
                http.requests(),
                Duration.ofNanos(System.nanoTime() - started),
                http.statusCounts(),
                http.routeCounts(),
                transfers);
    }

    /** Opening state, the load, then the closing state once the general ledger has caught up. */
    static TransferObservation sendTransfers(LoadHttp http, Options options) {
        Map<Long, Long> opening = ServerReads.discoverBalances(http);
        if (opening.size() < 2) {
            throw new IllegalStateException(
                    "the server has %d accounts; the load needs at least 2".formatted(opening.size()));
        }
        ServerReads.Metrics before = ServerReads.readMetrics(http);
        long totalMoney = opening.values().stream().mapToLong(Long::longValue).sum();
        String tag = Long.toString(System.currentTimeMillis(), 36);
        List<TransferPlan.Op> ops = TransferPlan.build(
                List.copyOf(opening.keySet()), totalMoney, options.seed(), options.operations(), tag);

        List<TransferLoad.Answer> answers = new TransferLoad(http, options.clients()).run(ops, options.seed());

        Map<Long, Long> closing = ServerReads.readBalances(http, List.copyOf(opening.keySet()), options.clients());
        long postings = answers.stream()
                .filter(a -> a.status() == 201 && a.decided())
                .map(TransferLoad.Answer::id)
                .distinct()
                .count();
        ServerReads.Metrics after = awaitGeneralLedger(http, before.glEntries() + postings);
        return new TransferObservation(ops, answers, opening, closing, before, after);
    }

    /** Bounded poll: the general ledger is fed by the outbox, so it may trail the ledger for a moment. */
    private static ServerReads.Metrics awaitGeneralLedger(LoadHttp http, long wantedEntries) {
        long deadline = System.nanoTime() + GL_DRAIN_LIMIT.toNanos();
        while (true) {
            ServerReads.Metrics metrics = ServerReads.readMetrics(http);
            if ((metrics.glEntries() >= wantedEntries && metrics.outboxSize() == 0) || System.nanoTime() > deadline) {
                return metrics;
            }
            try {
                Thread.sleep(50); // poll interval of a bounded wait, not a synchronisation
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return metrics;
            }
        }
    }
}
