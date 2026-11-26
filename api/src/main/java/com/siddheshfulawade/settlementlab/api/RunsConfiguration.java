package com.siddheshfulawade.settlementlab.api;

import java.time.Clock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import tools.jackson.databind.cfg.CoercionAction;
import tools.jackson.databind.cfg.CoercionInputShape;
import tools.jackson.databind.type.LogicalType;

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

    /** Every POST body is capped at 16 KB, in every profile (REQ-API-009). */
    @Bean
    BodyLimitFilter bodyLimitFilter() {
        return new BodyLimitFilter();
    }

    /** The prod profile answers 404 for everything but the runs API and health (REQ-API-010). */
    @Bean
    @Profile("prod")
    ProdAllowListFilter prodAllowListFilter() {
        return new ProdAllowListFilter();
    }

    /** A JSON number or boolean is not a string: {@code "id": 5} is a 400, not the id "5" (REQ-API-009). */
    @Bean
    JsonMapperBuilderCustomizer strictStrings() {
        return builder -> builder.withCoercionConfig(
                LogicalType.Textual,
                config -> config.setCoercion(CoercionInputShape.Integer, CoercionAction.Fail)
                        .setCoercion(CoercionInputShape.Float, CoercionAction.Fail)
                        .setCoercion(CoercionInputShape.Boolean, CoercionAction.Fail));
    }
}
