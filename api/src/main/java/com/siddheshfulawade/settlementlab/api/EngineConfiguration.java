package com.siddheshfulawade.settlementlab.api;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** The only place Spring meets the engine. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(SettlementProperties.class)
class EngineConfiguration {

    /** Spring calls {@link SettlementRuntime#close()} on shutdown, after in-flight requests finish. */
    @Bean(destroyMethod = "close")
    SettlementRuntime settlementRuntime(SettlementProperties properties) {
        return SettlementRuntime.start(properties);
    }
}
