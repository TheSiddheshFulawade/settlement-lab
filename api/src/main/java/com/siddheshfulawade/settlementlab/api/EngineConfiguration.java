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

    /** Closed after the runtime (it depends on the hub), so no event is sent to a closed hub. */
    @Bean(destroyMethod = "close")
    EventHub eventHub() {
        return new EventHub(EVENT_BUFFER);
    }

    /** Spring calls {@link SettlementRuntime#close()} on shutdown, after in-flight requests finish. */
    @Bean(destroyMethod = "close")
    SettlementRuntime settlementRuntime(SettlementProperties properties, EventHub eventHub) {
        return SettlementRuntime.start(properties, eventHub);
    }
}
