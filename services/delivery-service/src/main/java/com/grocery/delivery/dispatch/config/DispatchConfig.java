package com.grocery.delivery.dispatch.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(DispatchProperties.class)
public class DispatchConfig {

    /**
     * Runs the dispatch poller and (Phase 10) the location sample cleanup; {@code grocery.delivery.pollers-enabled=false}
     * switches both off.
     */
    @Configuration(proxyBeanMethods = false)
    @EnableScheduling
    @ConditionalOnProperty(prefix = "grocery.delivery", name = "pollers-enabled", matchIfMissing = true)
    static class SchedulingConfig {
    }
}
