package com.grocery.common.observability;

import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.PropertyResolver;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The platform's observability <b>defaults</b> (grocery-common 0.9.0, contract Phase 13 §1), added as the
 * lowest-precedence property source {@value #PROPERTY_SOURCE_NAME}: anything a service sets in its
 * {@code application.yml}, a profile, an environment variable or a system property wins.
 *
 * <table>
 *   <tr><th>Property</th><th>Default</th></tr>
 *   <tr><td>{@code management.endpoints.web.exposure.include}</td><td>{@code health,info,prometheus}</td></tr>
 *   <tr><td>{@code management.tracing.sampling.probability}</td><td>{@code ${TRACING_SAMPLING_PROBABILITY:1.0}}</td></tr>
 *   <tr><td>{@code management.opentelemetry.tracing.export.otlp.endpoint}</td>
 *       <td>{@code ${OTEL_EXPORTER_OTLP_ENDPOINT}/v1/traces}, else {@code http://localhost:4318/v1/traces}</td></tr>
 *   <tr><td>{@code spring.kafka.listener.observation-enabled}, {@code spring.kafka.template.observation-enabled}</td>
 *       <td>{@code true} (a consumed record with a {@code traceparent} header continues its trace)</td></tr>
 *   <tr><td>{@code logging.structured.ecs.service.environment} / {@code .node-name}</td><td>{@code GROCERY_ENV} / the instance</td></tr>
 *   <tr><td>{@code management.opentelemetry.resource-attributes[deployment.environment.name]} /
 *       {@code [service.instance.id]}</td><td>{@code GROCERY_ENV} / the instance</td></tr>
 *   <tr><td>{@code management.metrics.distribution.slo[http.server.requests]}, {@code [grocery.orders.placement]},
 *       {@code [grocery.ai.model.latency]}</td>
 *       <td>{@code ${grocery.observability.latency-slo:50ms,100ms,250ms,500ms,1s,2s,5s}}</td></tr>
 * </table>
 *
 * <p>{@code management.tracing.enabled=false} (the Spring Boot 3 name) is honoured as
 * {@code management.tracing.export.enabled=false} unless the latter is set. {@code grocery.observability.enabled=false}
 * adds none of these defaults.
 *
 * <p>Runs right after the config data (application.yml) is loaded, so it sees the service's own settings.
 */
public class GroceryObservabilityEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

    public static final String PROPERTY_SOURCE_NAME = "groceryObservabilityDefaults";
    public static final int ORDER = ConfigDataEnvironmentPostProcessor.ORDER + 1;

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        if (!GroceryObservability.isEnabled(environment)) {
            return;
        }
        environment.getPropertySources().remove(PROPERTY_SOURCE_NAME);
        environment.getPropertySources().addLast(new MapPropertySource(PROPERTY_SOURCE_NAME, defaults(environment)));
    }

    /** The defaults for this environment (exposed for tests and for runners that do not run post-processors). */
    public static Map<String, Object> defaults(PropertyResolver environment) {
        String env = GroceryObservability.environmentName(environment);
        String instance = GroceryObservability.instance(environment);
        Map<String, Object> defaults = new LinkedHashMap<>();
        defaults.put("management.endpoints.web.exposure.include", "health,info,prometheus");
        defaults.put("management.tracing.sampling.probability",
                "${" + GroceryObservability.SAMPLING_VARIABLE + ":" + GroceryObservability.DEFAULT_SAMPLING_PROBABILITY + "}");
        defaults.put("management.opentelemetry.tracing.export.otlp.endpoint", GroceryObservability.tracesEndpoint(environment));
        defaults.put("spring.kafka.listener.observation-enabled", "true");
        defaults.put("spring.kafka.template.observation-enabled", "true");
        defaults.put("logging.structured.ecs.service.environment", env);
        defaults.put("logging.structured.ecs.service.node-name", instance);
        defaults.put("management.opentelemetry.resource-attributes[deployment.environment.name]", env);
        defaults.put("management.opentelemetry.resource-attributes[service.instance.id]", instance);
        // explicit histogram buckets (not a full percentiles histogram) for the latency timers: p95 in Prometheus;
        // with tracing on, Spring Boot attaches exemplars (trace_id, span_id) to these buckets
        String slo = "${" + GroceryObservability.LATENCY_SLO_PROPERTY + ":" + GroceryObservability.DEFAULT_LATENCY_SLO + "}";
        for (String timer : GroceryObservability.LATENCY_TIMERS) {
            defaults.put("management.metrics.distribution.slo[" + timer + "]", slo);
        }
        if (Boolean.FALSE.equals(environment.getProperty("management.tracing.enabled", Boolean.class))
                && environment.getProperty("management.tracing.export.enabled") == null) {
            defaults.put("management.tracing.export.enabled", "false");
        }
        return defaults;
    }

    @Override
    public int getOrder() {
        return ORDER;
    }
}
