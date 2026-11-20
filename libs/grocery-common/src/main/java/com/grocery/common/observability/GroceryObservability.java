package com.grocery.common.observability;

import org.springframework.core.env.PropertyResolver;
import org.springframework.util.StringUtils;

import java.net.InetAddress;
import java.util.Locale;

/**
 * Names shared by the observability setup (grocery-common 0.9.0, contract Phase 13 §1): the common metric tags, the
 * environment variables and how {@code service}, {@code env} and {@code instance} are determined.
 */
public final class GroceryObservability {

    /** Common tag on every meter: {@code spring.application.name}. */
    public static final String SERVICE_TAG = "service";
    /** Common tag on every meter: {@code GROCERY_ENV} (default {@code local}). */
    public static final String ENV_TAG = "env";
    /** Common tag on every meter: the host name (the container id in Docker). */
    public static final String INSTANCE_TAG = "instance";

    /** The deployment environment ({@code local}, {@code dev}, {@code prod} …). */
    public static final String ENV_VARIABLE = "GROCERY_ENV";
    public static final String DEFAULT_ENV = "local";
    /** Base URL of the OTLP/HTTP collector (the signal path {@code /v1/traces} is appended). */
    public static final String OTLP_ENDPOINT_VARIABLE = "OTEL_EXPORTER_OTLP_ENDPOINT";
    public static final String DEFAULT_OTLP_ENDPOINT = "http://localhost:4318";
    /** Share of new traces that are sampled (a trace started upstream keeps the upstream decision). */
    public static final String SAMPLING_VARIABLE = "TRACING_SAMPLING_PROBABILITY";
    public static final String DEFAULT_SAMPLING_PROBABILITY = "1.0";

    /** {@code grocery.observability.enabled=false} switches the library's observability setup off. */
    public static final String ENABLED_PROPERTY = "grocery.observability.enabled";

    /**
     * Histogram buckets (Micrometer service-level objectives) of the latency timers in {@link #LATENCY_TIMERS}: explicit
     * buckets, so Prometheus can compute p95 with {@code histogram_quantile} without a full percentiles histogram.
     */
    public static final String LATENCY_SLO_PROPERTY = "grocery.observability.latency-slo";
    public static final String DEFAULT_LATENCY_SLO = "50ms,100ms,250ms,500ms,1s,2s,5s";
    /** The timers that get {@link #LATENCY_SLO_PROPERTY} by default (Spring's HTTP server timer and the services' own). */
    public static final java.util.List<String> LATENCY_TIMERS = java.util.List.of(
            "http.server.requests", "grocery.orders.placement", "grocery.ai.model.latency");

    private static volatile String hostName;

    private GroceryObservability() {
    }

    public static boolean isEnabled(PropertyResolver environment) {
        return environment.getProperty(ENABLED_PROPERTY, Boolean.class, true);
    }

    /** {@code spring.application.name}, or {@code unknown}. */
    public static String serviceName(PropertyResolver environment) {
        String name = environment.getProperty("spring.application.name");
        return StringUtils.hasText(name) ? name.trim() : "unknown";
    }

    /** {@code GROCERY_ENV} (an environment variable or a property of that name), or {@code local}. */
    public static String environmentName(PropertyResolver environment) {
        String name = environment.getProperty(ENV_VARIABLE);
        return StringUtils.hasText(name) ? name.trim().toLowerCase(Locale.ROOT) : DEFAULT_ENV;
    }

    /**
     * This instance: {@code HOSTNAME} (Docker sets it to the container id, Kubernetes to the pod name), else
     * {@code COMPUTERNAME} (Windows), else the local host name, else {@code unknown}. Resolved once per process.
     */
    public static String instance(PropertyResolver environment) {
        for (String variable : new String[]{"HOSTNAME", "COMPUTERNAME"}) {
            String value = environment.getProperty(variable);
            if (StringUtils.hasText(value)) {
                return value.trim();
            }
        }
        String known = hostName;
        if (known == null) {
            try {
                known = InetAddress.getLocalHost().getHostName();
            } catch (Exception | Error e) {
                known = "unknown";
            }
            hostName = known;
        }
        return known;
    }

    /** {@code <base>/v1/traces} from {@code OTEL_EXPORTER_OTLP_ENDPOINT}, else from {@code http://localhost:4318}. */
    public static String tracesEndpoint(PropertyResolver environment) {
        String base = environment.getProperty(OTLP_ENDPOINT_VARIABLE);
        base = StringUtils.hasText(base) ? base.trim() : DEFAULT_OTLP_ENDPOINT;
        return (base.endsWith("/") ? base : base + "/") + "v1/traces";
    }
}
