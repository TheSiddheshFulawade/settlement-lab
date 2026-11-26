package com.siddheshfulawade.settlementlab.api;

import com.siddheshfulawade.settlementlab.pipeline.EngineMetrics;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** {@code GET /api/v1/metrics} (REQ-API-004): what the runner's table shows, live. */
@Profile("!prod") // REQ-API-010: the prod profile serves only the runs API and health
@RestController
@RequestMapping("/api/v1/metrics")
class MetricsController {

    record Settings(String ledger, String threads, int workers, String faults) {}

    record Outbox(long size, long peak, long published, long failedAttempts) {}

    record Breaker(String state, long opens, long retries) {}

    record GeneralLedger(long entries, long duplicatePublishes) {}

    record MetricsResponse(
            Settings settings, EngineMetrics engine, Outbox outbox, Breaker breaker, GeneralLedger generalLedger) {}

    private final SettlementRuntime runtime;

    MetricsController(SettlementRuntime runtime) {
        this.runtime = runtime;
    }

    @GetMapping
    MetricsResponse metrics() {
        SettlementProperties p = runtime.properties();
        var outbox = runtime.outbox();
        var resilient = runtime.resilient();
        var gl = runtime.generalLedger();
        return new MetricsResponse(
                new Settings(
                        p.ledger().name(),
                        p.threads().name(),
                        p.workers(),
                        p.faults().name()),
                runtime.engine().metrics(),
                new Outbox(outbox.outboxSize(), outbox.outboxPeak(), outbox.published(), outbox.failedAttempts()),
                new Breaker(resilient.breakerState(), resilient.breakerOpens(), resilient.retries()),
                new GeneralLedger(gl.entries().size(), gl.duplicatePublishes()));
    }
}
