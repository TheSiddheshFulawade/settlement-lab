package com.siddheshfulawade.settlementlab.api;

import com.siddheshfulawade.settlementlab.domain.AccountId;
import com.siddheshfulawade.settlementlab.domain.CasLedger;
import com.siddheshfulawade.settlementlab.domain.Ledger;
import com.siddheshfulawade.settlementlab.domain.LockingLedger;
import com.siddheshfulawade.settlementlab.domain.Money;
import com.siddheshfulawade.settlementlab.gl.FakeGeneralLedger;
import com.siddheshfulawade.settlementlab.gl.FaultProfile;
import com.siddheshfulawade.settlementlab.gl.OutboxPublisher;
import com.siddheshfulawade.settlementlab.pipeline.EngineConfig;
import com.siddheshfulawade.settlementlab.pipeline.SettlementEngine;
import com.siddheshfulawade.settlementlab.pipeline.SettlementListener;
import com.siddheshfulawade.settlementlab.resilience.ResilienceConfig;
import com.siddheshfulawade.settlementlab.resilience.ResilientPublisher;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The running engine stack: ledger → engine → outbox → retry and breaker → fake general ledger.
 * Plain Java on purpose (no Spring types), so the start and stop order is explicit and testable.
 *
 * <p>Stop order (REQ-API-006): close the engine first (refuses new work, drains, waits for
 * workers), then drain the outbox so the general ledger catches up, then close the outbox.
 */
public final class SettlementRuntime implements AutoCloseable {

    private static final Duration OUTBOX_RETRY = Duration.ofMillis(20);

    private final SettlementProperties properties;
    private final Ledger ledger;
    private final FakeGeneralLedger generalLedger;
    private final ResilientPublisher resilient;
    private final OutboxPublisher outbox;
    private final SettlementEngine engine;
    private final AtomicBoolean closed = new AtomicBoolean();

    private SettlementRuntime(SettlementProperties properties, SettlementListener listener, FaultProfile faultProfile) {
        this.properties = properties;
        this.ledger = createLedger(properties);
        this.generalLedger = new FakeGeneralLedger(faultProfile, properties.seed());
        this.resilient = new ResilientPublisher(generalLedger, ResilienceConfig.defaults());
        this.outbox = new OutboxPublisher(resilient, OUTBOX_RETRY);
        this.engine = new SettlementEngine(
                ledger,
                new EngineConfig(
                        properties.threads(),
                        properties.workers(),
                        properties.queueCapacity(),
                        properties.offerTimeout(),
                        properties.drainTimeout()),
                listener,
                outbox);
    }

    public static SettlementRuntime start(SettlementProperties properties) {
        return start(properties, SettlementListener.NONE);
    }

    public static SettlementRuntime start(SettlementProperties properties, SettlementListener listener) {
        return new SettlementRuntime(properties, listener, properties.faultProfile());
    }

    /** As above, but with an explicit fault profile (a run sets its own outage window). */
    public static SettlementRuntime start(
            SettlementProperties properties, SettlementListener listener, FaultProfile faultProfile) {
        return new SettlementRuntime(properties, listener, faultProfile);
    }

    private static Ledger createLedger(SettlementProperties properties) {
        Map<AccountId, Money> opening = new LinkedHashMap<>();
        for (long id = 1; id <= properties.accounts(); id++) {
            opening.put(AccountId.of(id), Money.of(properties.openingBalance()));
        }
        return properties.ledger() == LedgerChoice.LOCKING ? new LockingLedger(opening) : new CasLedger(opening);
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        engine.close();
        outbox.drain(properties.drainTimeout());
        outbox.close();
    }

    public SettlementProperties properties() {
        return properties;
    }

    public Ledger ledger() {
        return ledger;
    }

    public SettlementEngine engine() {
        return engine;
    }

    public FakeGeneralLedger generalLedger() {
        return generalLedger;
    }

    public OutboxPublisher outbox() {
        return outbox;
    }

    public ResilientPublisher resilient() {
        return resilient;
    }
}
