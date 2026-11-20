package com.grocery.common.observability;

import io.micrometer.tracing.otel.bridge.OtelCurrentTraceContext;
import io.micrometer.tracing.otel.bridge.OtelTracer;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import org.springframework.boot.micrometer.tracing.opentelemetry.autoconfigure.OpenTelemetryEventPublisherBeansApplicationListener;

/**
 * Tracing in tests. OpenTelemetry's context storage takes wrappers only before its first use in the JVM, and Spring
 * Boot's wrapper is what puts {@code traceId} / {@code spanId} into the MDC: every test that touches OpenTelemetry calls
 * {@link #init()} first, so the test classes can run in any order.
 */
public final class TestTracing {

    static {
        OpenTelemetryEventPublisherBeansApplicationListener.addWrapper();
    }

    private TestTracing() {
    }

    /** Installs Spring Boot's context-storage wrapper (idempotent). */
    public static void init() {
        // the static initializer did it
    }

    /** A Micrometer tracer over a real OpenTelemetry SDK that exports every ended span to {@code exporter}. */
    public static OtelTracer tracer(InMemorySpanExporter exporter) {
        init();
        SdkTracerProvider provider = SdkTracerProvider.builder()
                .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                .build();
        return new OtelTracer(provider.get("grocery-common-test"), new OtelCurrentTraceContext(), event -> {
        });
    }
}
