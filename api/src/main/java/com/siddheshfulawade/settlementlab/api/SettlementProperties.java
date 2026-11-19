package com.siddheshfulawade.settlementlab.api;

import com.siddheshfulawade.settlementlab.gl.FaultProfile;
import com.siddheshfulawade.settlementlab.pipeline.ThreadMode;
import java.time.Duration;
import java.util.Objects;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code settlement.*} settings, bound once at start-up and checked here, so a bad value stops the
 * application instead of failing the first request. Defaults: see {@link #defaults()}.
 */
@ConfigurationProperties("settlement")
public record SettlementProperties(
        @DefaultValue("LOCKING") LedgerChoice ledger,
        @DefaultValue("VIRTUAL") ThreadMode threads,
        @DefaultValue("1000") int accounts,
        @DefaultValue("8") int workers,
        @DefaultValue("1024") int queueCapacity,
        @DefaultValue("10000") long openingBalance,
        @DefaultValue("100ms") Duration offerTimeout,
        @DefaultValue("10s") Duration drainTimeout,
        @DefaultValue("NONE") FaultChoice faults,
        @DefaultValue("20261119") long seed) {

    public SettlementProperties {
        Objects.requireNonNull(ledger, "ledger");
        Objects.requireNonNull(threads, "threads");
        Objects.requireNonNull(offerTimeout, "offerTimeout");
        Objects.requireNonNull(drainTimeout, "drainTimeout");
        Objects.requireNonNull(faults, "faults");
        if (accounts < 2) {
            throw new IllegalArgumentException("settlement.accounts must be at least 2");
        }
        if (openingBalance < 0) {
            throw new IllegalArgumentException("settlement.opening-balance must not be negative");
        }
        if (drainTimeout.isNegative()) {
            throw new IllegalArgumentException("settlement.drain-timeout must not be negative");
        }
        // workers, queue capacity and offer timeout are checked by EngineConfig.
    }

    /** The same values as the {@code @DefaultValue}s above. */
    public static SettlementProperties defaults() {
        return new SettlementProperties(
                LedgerChoice.LOCKING,
                ThreadMode.VIRTUAL,
                1_000,
                8,
                1_024,
                10_000,
                Duration.ofMillis(100),
                Duration.ofSeconds(10),
                FaultChoice.NONE,
                20_261_119);
    }

    public FaultProfile faultProfile() {
        return switch (faults) {
            case NONE -> FaultProfile.NONE;
            case FLAKY -> FaultProfile.FLAKY;
            case DUPLICATE_ACKS -> FaultProfile.DUPLICATE_ACKS;
            case OUTAGE -> FaultProfile.outage(Duration.ofSeconds(30), Duration.ofSeconds(10));
        };
    }

    public SettlementProperties withLedger(LedgerChoice value) {
        return new SettlementProperties(
                value,
                threads,
                accounts,
                workers,
                queueCapacity,
                openingBalance,
                offerTimeout,
                drainTimeout,
                faults,
                seed);
    }

    public SettlementProperties withThreads(ThreadMode value) {
        return new SettlementProperties(
                ledger,
                value,
                accounts,
                workers,
                queueCapacity,
                openingBalance,
                offerTimeout,
                drainTimeout,
                faults,
                seed);
    }

    public SettlementProperties withFaults(FaultChoice value) {
        return new SettlementProperties(
                ledger,
                threads,
                accounts,
                workers,
                queueCapacity,
                openingBalance,
                offerTimeout,
                drainTimeout,
                value,
                seed);
    }
}
