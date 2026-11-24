package com.siddheshfulawade.settlementlab.api;

import com.siddheshfulawade.settlementlab.gl.FaultProfile;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code settlement.runs.*}: the numbers of the runs API (SPEC section 18). The defaults are the contract's
 * numbers (docs/api-contract.md); tests and local runs override them. Bound once at start-up, so a nonsense
 * value stops the application instead of failing a visitor's request.
 *
 * @param previewOrigin the one extra CORS origin (the Cloudflare Pages preview); empty means none
 * @param deadline wall time after which a run stops submitting (it then drains and reports)
 * @param retention how long a finished run's report is kept
 * @param sampleInterval time between {@code metrics} events
 * @param perIpPerHour runs one visitor may start per hour
 * @param globalPerHour runs all visitors together may start per hour
 * @param maxActive runs allowed at the same time
 * @param subscribersPerRun event streams one run serves at once
 * @param eventBuffer events one subscriber may have waiting before the oldest metrics are dropped
 * @param transfersPerSecond the steady rate at which a run submits transfers
 * @param trustedProxyRanges CIDR ranges of our own proxies, skipped when looking for the visitor
 * @param outageStart when an OUTAGE run's general ledger starts refusing, from the run's start
 * @param outageLength how long it refuses
 */
@ConfigurationProperties("settlement.runs")
public record RunsProperties(
        @DefaultValue("") String previewOrigin,
        @DefaultValue("20s") Duration deadline,
        @DefaultValue("10m") Duration retention,
        @DefaultValue("250ms") Duration sampleInterval,
        @DefaultValue("5") int perIpPerHour,
        @DefaultValue("60") int globalPerHour,
        @DefaultValue("2") int maxActive,
        @DefaultValue("2") int subscribersPerRun,
        @DefaultValue("64") int eventBuffer,
        @DefaultValue("2000") int transfersPerSecond,
        List<String> trustedProxyRanges,
        @DefaultValue("1s") Duration outageStart,
        @DefaultValue("3s") Duration outageLength) {

    public RunsProperties {
        previewOrigin = previewOrigin == null ? "" : previewOrigin.trim();
        trustedProxyRanges = trustedProxyRanges == null ? List.of() : List.copyOf(trustedProxyRanges);
        Objects.requireNonNull(deadline, "deadline");
        Objects.requireNonNull(retention, "retention");
        Objects.requireNonNull(sampleInterval, "sampleInterval");
        Objects.requireNonNull(outageStart, "outageStart");
        Objects.requireNonNull(outageLength, "outageLength");
        require(deadline.isPositive(), "deadline must be positive");
        require(!retention.isNegative(), "retention must not be negative");
        require(sampleInterval.isPositive(), "sample-interval must be positive");
        require(perIpPerHour >= 1 && globalPerHour >= 1 && maxActive >= 1, "run limits must be at least 1");
        require(subscribersPerRun >= 1 && eventBuffer >= 1, "subscribers-per-run and event-buffer must be at least 1");
        require(transfersPerSecond >= 1, "transfers-per-second must be at least 1");
        require(!outageStart.isNegative() && !outageLength.isNegative(), "outage times must not be negative");
    }

    /** The same values as the {@code @DefaultValue}s above. */
    public static RunsProperties defaults() {
        return new RunsProperties(
                "",
                Duration.ofSeconds(20),
                Duration.ofMinutes(10),
                Duration.ofMillis(250),
                5,
                60,
                2,
                2,
                64,
                2_000,
                List.of(),
                Duration.ofSeconds(1),
                Duration.ofSeconds(3));
    }

    /** The general ledger's faults for a run: the named profile, with this run's outage window. */
    FaultProfile faultProfile(FaultChoice choice) {
        return switch (choice) {
            case NONE -> FaultProfile.NONE;
            case FLAKY -> FaultProfile.FLAKY;
            case OUTAGE -> FaultProfile.outage(outageStart, outageLength);
            case DUPLICATE_ACKS -> throw new IllegalArgumentException("not offered to visitors: " + choice);
        };
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalArgumentException("settlement.runs: " + message);
        }
    }

    public RunsProperties withPreviewOrigin(String value) {
        return new RunsProperties(
                value,
                deadline,
                retention,
                sampleInterval,
                perIpPerHour,
                globalPerHour,
                maxActive,
                subscribersPerRun,
                eventBuffer,
                transfersPerSecond,
                trustedProxyRanges,
                outageStart,
                outageLength);
    }

    public RunsProperties withDeadline(Duration value) {
        return new RunsProperties(
                previewOrigin,
                value,
                retention,
                sampleInterval,
                perIpPerHour,
                globalPerHour,
                maxActive,
                subscribersPerRun,
                eventBuffer,
                transfersPerSecond,
                trustedProxyRanges,
                outageStart,
                outageLength);
    }

    public RunsProperties withRetention(Duration value) {
        return new RunsProperties(
                previewOrigin,
                deadline,
                value,
                sampleInterval,
                perIpPerHour,
                globalPerHour,
                maxActive,
                subscribersPerRun,
                eventBuffer,
                transfersPerSecond,
                trustedProxyRanges,
                outageStart,
                outageLength);
    }

    public RunsProperties withSampleInterval(Duration value) {
        return new RunsProperties(
                previewOrigin,
                deadline,
                retention,
                value,
                perIpPerHour,
                globalPerHour,
                maxActive,
                subscribersPerRun,
                eventBuffer,
                transfersPerSecond,
                trustedProxyRanges,
                outageStart,
                outageLength);
    }

    public RunsProperties withPerIpPerHour(int value) {
        return new RunsProperties(
                previewOrigin,
                deadline,
                retention,
                sampleInterval,
                value,
                globalPerHour,
                maxActive,
                subscribersPerRun,
                eventBuffer,
                transfersPerSecond,
                trustedProxyRanges,
                outageStart,
                outageLength);
    }

    public RunsProperties withGlobalPerHour(int value) {
        return new RunsProperties(
                previewOrigin,
                deadline,
                retention,
                sampleInterval,
                perIpPerHour,
                value,
                maxActive,
                subscribersPerRun,
                eventBuffer,
                transfersPerSecond,
                trustedProxyRanges,
                outageStart,
                outageLength);
    }

    public RunsProperties withMaxActive(int value) {
        return new RunsProperties(
                previewOrigin,
                deadline,
                retention,
                sampleInterval,
                perIpPerHour,
                globalPerHour,
                value,
                subscribersPerRun,
                eventBuffer,
                transfersPerSecond,
                trustedProxyRanges,
                outageStart,
                outageLength);
    }

    public RunsProperties withSubscribersPerRun(int value) {
        return new RunsProperties(
                previewOrigin,
                deadline,
                retention,
                sampleInterval,
                perIpPerHour,
                globalPerHour,
                maxActive,
                value,
                eventBuffer,
                transfersPerSecond,
                trustedProxyRanges,
                outageStart,
                outageLength);
    }

    public RunsProperties withEventBuffer(int value) {
        return new RunsProperties(
                previewOrigin,
                deadline,
                retention,
                sampleInterval,
                perIpPerHour,
                globalPerHour,
                maxActive,
                subscribersPerRun,
                value,
                transfersPerSecond,
                trustedProxyRanges,
                outageStart,
                outageLength);
    }

    public RunsProperties withTransfersPerSecond(int value) {
        return new RunsProperties(
                previewOrigin,
                deadline,
                retention,
                sampleInterval,
                perIpPerHour,
                globalPerHour,
                maxActive,
                subscribersPerRun,
                eventBuffer,
                value,
                trustedProxyRanges,
                outageStart,
                outageLength);
    }

    public RunsProperties withTrustedProxyRanges(List<String> value) {
        return new RunsProperties(
                previewOrigin,
                deadline,
                retention,
                sampleInterval,
                perIpPerHour,
                globalPerHour,
                maxActive,
                subscribersPerRun,
                eventBuffer,
                transfersPerSecond,
                value,
                outageStart,
                outageLength);
    }

    public RunsProperties withOutage(Duration start, Duration length) {
        return new RunsProperties(
                previewOrigin,
                deadline,
                retention,
                sampleInterval,
                perIpPerHour,
                globalPerHour,
                maxActive,
                subscribersPerRun,
                eventBuffer,
                transfersPerSecond,
                trustedProxyRanges,
                start,
                length);
    }
}
