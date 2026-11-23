package com.siddheshfulawade.settlementlab.runner;

import com.siddheshfulawade.settlementlab.domain.AccountId;
import com.siddheshfulawade.settlementlab.domain.InvariantChecker;
import com.siddheshfulawade.settlementlab.domain.InvariantReport;
import com.siddheshfulawade.settlementlab.domain.InvariantResult;
import com.siddheshfulawade.settlementlab.domain.Ledger;
import com.siddheshfulawade.settlementlab.domain.Money;
import com.siddheshfulawade.settlementlab.domain.Outcome;
import com.siddheshfulawade.settlementlab.domain.PostingPublisher;
import com.siddheshfulawade.settlementlab.domain.Transfer;
import com.siddheshfulawade.settlementlab.domain.TransferId;
import com.siddheshfulawade.settlementlab.gl.FakeGeneralLedger;
import com.siddheshfulawade.settlementlab.gl.FaultProfile;
import com.siddheshfulawade.settlementlab.gl.OutboxPublisher;
import com.siddheshfulawade.settlementlab.pipeline.EngineConfig;
import com.siddheshfulawade.settlementlab.pipeline.EngineMetrics;
import com.siddheshfulawade.settlementlab.pipeline.SettlementEngine;
import com.siddheshfulawade.settlementlab.pipeline.SettlementListener;
import com.siddheshfulawade.settlementlab.pipeline.ThreadMode;
import com.siddheshfulawade.settlementlab.resilience.ResilientPublisher;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Runs one scenario: wires ledger, engine, resilient publisher, outbox and a faulty general ledger;
 * drives the workload with closed-loop clients; drains; checks every invariant (REQ-LAB-015).
 */
public final class ScenarioRunner {

    public ScenarioResult run(Scenario scenario) {
        LedgerType type = scenario.ledger();
        LedgerFactory factory = new LedgerFactory() {
            @Override
            public String name() {
                return type.name();
            }

            @Override
            public Ledger create(Map<AccountId, Money> opening) {
                return type.create(opening);
            }
        };
        return run(factory, scenario.threads(), scenario.faults(), scenario.workload(), scenario.settings());
    }

    /** Runs a caller-supplied ledger (for example the database cells); the row is named by the factory. */
    public ScenarioResult run(
            LedgerFactory factory,
            ThreadMode threadMode,
            FaultProfile faults,
            Workload workload,
            RunnerSettings settings) {
        Map<AccountId, Money> opening = workload.openingBalances();
        var gl = new FakeGeneralLedger(faults, workload.seed());
        var resilient = new ResilientPublisher(gl, settings.resilience());
        var config = new EngineConfig(
                threadMode,
                settings.workers(),
                settings.queueCapacity(),
                settings.offerTimeout(),
                settings.drainTimeout());
        long[] latencies = new long[workload.transfers()];
        Set<TransferId> posted = ConcurrentHashMap.newKeySet();

        Ledger ledger = factory.create(opening);
        Optional<GlDelivery> delivery = factory.deliveryTo(resilient);
        // Without a delivery the engine publishes through the in-memory outbox; with one, the ledger
        // delivers and the engine publishes nothing, so no posting is sent twice.
        try (var outbox = new OutboxPublisher(resilient, settings.outboxRetry());
                var engine = new SettlementEngine(
                        ledger,
                        config,
                        SettlementListener.NONE,
                        delivery.isPresent() ? PostingPublisher.NONE : outbox)) {
            long start = System.nanoTime();
            drive(engine, workload, settings.clients(), latencies, posted);
            engine.drain(settings.drainTimeout());
            long elapsed = System.nanoTime() - start;
            // Invariant: after this drain the general ledger holds exactly the ledger's postings.
            boolean glCaughtUp = delivery.map(d -> d.drain(settings.drainTimeout()))
                    .orElseGet(() -> outbox.drain(settings.drainTimeout()));
            long outboxPeak = delivery.map(GlDelivery::outboxPeak).orElseGet(outbox::outboxPeak);

            InvariantReport report =
                    InvariantChecker.checkRun(opening, engine.snapshot(), engine.postings(), posted, gl.entries());
            EngineMetrics metrics = engine.metrics();
            String failure = report.firstFailure()
                    .map(InvariantResult::toString)
                    .orElse(glCaughtUp ? "" : "general ledger did not catch up before the drain timeout");
            return new ScenarioResult(
                    factory.name(),
                    threadMode.name(),
                    faults.name(),
                    workload.transfers(),
                    Math.round(workload.transfers() / (elapsed / 1e9)),
                    Percentiles.of(latencies, 50) / 1_000,
                    Percentiles.of(latencies, 99) / 1_000,
                    metrics.rejectedFunds()
                            + metrics.rejectedInvalid()
                            + metrics.queueFull()
                            // contention is a refusal that stored nothing, like queue-full
                            + metrics.rejectedContention(),
                    metrics.duplicates(),
                    resilient.retries(),
                    resilient.breakerOpens(),
                    outboxPeak,
                    report.allPassed() && glCaughtUp,
                    failure);
        } finally {
            // Invariant: a delivery (background threads) is closed on every path, success or failure.
            delivery.ifPresent(GlDelivery::close);
        }
    }

    /** Closed loop: each client submits, waits for the answer, records the latency, repeats. */
    private static void drive(
            SettlementEngine engine, Workload workload, int clients, long[] latencies, Set<TransferId> posted) {
        AtomicInteger next = new AtomicInteger();
        List<Thread> threads = new ArrayList<>();
        for (int c = 0; c < clients; c++) {
            threads.add(Thread.ofVirtual().name("client-" + c).start(() -> {
                for (int i = next.getAndIncrement(); i < workload.transfers(); i = next.getAndIncrement()) {
                    Transfer transfer = workload.transfer(i);
                    long sent = System.nanoTime();
                    Outcome outcome =
                            engine.submit(transfer).toCompletableFuture().join();
                    latencies[i] = System.nanoTime() - sent;
                    if (outcome == Outcome.POSTED) {
                        posted.add(transfer.id());
                    }
                }
            }));
        }
        for (Thread thread : threads) {
            try {
                thread.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted while driving the workload", e);
            }
        }
    }
}
