package com.siddheshfulawade.settlementlab.postgres;

import com.siddheshfulawade.settlementlab.domain.AccountId;
import com.siddheshfulawade.settlementlab.domain.Ledger;
import com.siddheshfulawade.settlementlab.domain.Money;
import com.siddheshfulawade.settlementlab.gl.GeneralLedgerPort;
import com.siddheshfulawade.settlementlab.runner.GlDelivery;
import com.siddheshfulawade.settlementlab.runner.LedgerFactory;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;
import javax.sql.DataSource;

/**
 * A database cell for the runner (test sources, so Testcontainers stays test-scoped): seeds the
 * accounts, builds a {@link PostgresLedger} in one lock mode, and delivers to the general ledger
 * through the {@link OutboxRelay} (SPEC 16.4); the engine itself publishes nothing.
 */
final class PostgresLedgerFactory implements LedgerFactory {

    private final DataSource dataSource;
    private final LockMode mode;

    PostgresLedgerFactory(DataSource dataSource, LockMode mode) {
        this.dataSource = dataSource;
        this.mode = mode;
    }

    @Override
    public String name() {
        return "POSTGRES_" + mode.name();
    }

    @Override
    public Ledger create(Map<AccountId, Money> opening) {
        LedgerSeed.reset(dataSource, opening);
        return new PostgresLedger(dataSource, mode);
    }

    @Override
    public Optional<GlDelivery> deliveryTo(GeneralLedgerPort gl) {
        OutboxRelay relay = new OutboxRelay(dataSource, gl, 100, Duration.ofSeconds(5));
        AtomicBoolean running = new AtomicBoolean(true);
        AtomicLong peak = new AtomicLong();
        // Daemon, and survives a database error: a failed pass backs off and tries again, so the peak keeps
        // being measured; drain() below is what guarantees delivery either way.
        Thread background = Thread.ofPlatform()
                .name("outbox-relay")
                .daemon(true)
                .start(() -> {
                    while (running.get()) {
                        int published;
                        try {
                            peak.accumulateAndGet(OutboxTestSupport.count(dataSource, "outbox"), Math::max);
                            published = relay.relayOnce();
                        } catch (RuntimeException e) {
                            published = 0;
                        }
                        if (published == 0) {
                            LockSupport.parkNanos(Duration.ofMillis(20).toNanos());
                        }
                    }
                });
        return Optional.of(new GlDelivery() {
            @Override
            public boolean drain(Duration timeout) {
                return relay.drain(timeout);
            }

            @Override
            public long outboxPeak() {
                return peak.get();
            }

            @Override
            public void close() {
                running.set(false);
                try {
                    background.join(5_000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        });
    }
}
