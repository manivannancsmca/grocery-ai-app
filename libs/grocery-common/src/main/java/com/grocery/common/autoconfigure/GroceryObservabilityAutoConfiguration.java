package com.grocery.common.autoconfigure;

import com.grocery.common.observability.GroceryEventMetrics;
import com.grocery.common.observability.GroceryObservability;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.micrometer.metrics.autoconfigure.MeterRegistryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;

/**
 * Observability of every service (grocery-common 0.9.0, contract Phase 13 §1):
 * <ul>
 *   <li>common tags on every meter: {@code service} ({@code spring.application.name}), {@code env}
 *       ({@code GROCERY_ENV}, default {@code local}) and {@code instance} (the host name);</li>
 *   <li>{@link GroceryEventMetrics}: {@code grocery_events_published_total}, {@code grocery_events_consumed_total}
 *       (and the dead-letter gauge, registered by {@link GroceryDeadLetterAutoConfiguration}).</li>
 * </ul>
 * The Prometheus registry, Micrometer Tracing with the OpenTelemetry bridge and the OTLP exporter are Spring Boot's own
 * auto-configurations (dependencies of this library); their grocery defaults (Prometheus endpoint exposed, sampling,
 * OTLP endpoint, Kafka observation, ECS log fields) come from
 * {@link com.grocery.common.observability.GroceryObservabilityEnvironmentPostProcessor}.
 * {@code grocery.observability.enabled=false} switches all of it off. Every bean backs off if the service defines its own.
 */
@AutoConfiguration
@ConditionalOnClass(MeterRegistry.class)
@ConditionalOnProperty(prefix = "grocery.observability", name = "enabled", matchIfMissing = true)
public class GroceryObservabilityAutoConfiguration {

    /** {@code service}, {@code env}, {@code instance} on every meter of every registry. */
    @Bean
    @ConditionalOnMissingBean(name = "groceryCommonTagsCustomizer")
    MeterRegistryCustomizer<MeterRegistry> groceryCommonTagsCustomizer(Environment environment) {
        String service = GroceryObservability.serviceName(environment);
        String env = GroceryObservability.environmentName(environment);
        String instance = GroceryObservability.instance(environment);
        return registry -> registry.config().commonTags(
                GroceryObservability.SERVICE_TAG, service,
                GroceryObservability.ENV_TAG, env,
                GroceryObservability.INSTANCE_TAG, instance);
    }

    /** No-op without a {@link MeterRegistry} (a service without Micrometer's auto-configuration). */
    @Bean
    @ConditionalOnMissingBean
    GroceryEventMetrics groceryEventMetrics(ObjectProvider<MeterRegistry> meterRegistry) {
        return new GroceryEventMetrics(meterRegistry.getIfUnique());
    }
}
