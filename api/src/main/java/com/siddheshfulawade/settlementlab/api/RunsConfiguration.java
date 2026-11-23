package com.siddheshfulawade.settlementlab.api;

import java.time.Clock;
import java.util.Set;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;

/** Wires the runs API: limits, registry, CORS and the body cap. Active in every profile, {@code prod} included. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(RunsProperties.class)
class RunsConfiguration {

    /** Wall time for deadlines, retention and the rate-limit windows; tests replace it with a movable clock. */
    @Bean
    Clock runClock() {
        return Clock.systemUTC();
    }

    /** Fails the start-up if a trusted proxy range is not valid (REQ-RUN-006). */
    @Bean
    ClientIp clientIp(RunsProperties properties) {
        return new ClientIp(properties.trustedProxyRanges());
    }

    /** Closed by Spring on shutdown; {@link RunStreamShutdown} closes it earlier so streams end first. */
    @Bean(destroyMethod = "close")
    RunService runService(RunsProperties properties, Clock clock) {
        return new RunService(properties, clock);
    }

    @Bean
    RunStreamShutdown runStreamShutdown(RunService service) {
        return new RunStreamShutdown(service);
    }

    @Bean
    RunCorsFilter runCorsFilter(RunsProperties properties) {
        return new RunCorsFilter(properties.previewOrigin());
    }

    /** The runs endpoint always; the local transfer endpoint too unless the {@code prod} profile hides it. */
    @Bean
    BodyLimitFilter bodyLimitFilter(Environment environment) {
        boolean prod = environment.acceptsProfiles(Profiles.of("prod"));
        return new BodyLimitFilter(prod ? Set.of("/api/v1/runs") : Set.of("/api/v1/runs", "/api/v1/transfers"));
    }
}
