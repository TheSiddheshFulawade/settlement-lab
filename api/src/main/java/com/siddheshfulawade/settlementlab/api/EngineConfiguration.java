package com.siddheshfulawade.settlementlab.api;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** The only place Spring meets the engine. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(SettlementProperties.class)
class EngineConfiguration {

    /** Events per stream subscriber that may wait before the subscriber counts as too slow. */
    static final int EVENT_BUFFER = 1_024;

    /**
     * {@link EventStreamShutdown} closes the hub when shutdown starts, so the engine may still report
     * outcomes to a closed hub while accepted transfers drain; that is harmless (no subscribers). The
     * destroy method closes it again, which is idempotent.
     */
    @Bean(destroyMethod = "close")
    EventHub eventHub() {
        return new EventHub(EVENT_BUFFER);
    }

    /** Ends idle streams before the web server's graceful shutdown starts waiting for them. */
    @Bean
    EventStreamShutdown eventStreamShutdown(EventHub eventHub) {
        return new EventStreamShutdown(eventHub);
    }

    /** Spring calls {@link SettlementRuntime#close()} on shutdown, after in-flight requests finish. */
    @Bean(destroyMethod = "close")
    SettlementRuntime settlementRuntime(SettlementProperties properties, EventHub eventHub) {
        return SettlementRuntime.start(properties, eventHub);
    }
}
