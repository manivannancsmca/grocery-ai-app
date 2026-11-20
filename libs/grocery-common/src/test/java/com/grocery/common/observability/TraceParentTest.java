package com.grocery.common.observability;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.otel.bridge.OtelTracer;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TraceParentTest {

    private static TraceContext context(String traceId, String spanId, Boolean sampled) {
        return new TraceContext() {
            @Override
            public String traceId() {
                return traceId;
            }

            @Override
            public String parentId() {
                return null;
            }

            @Override
            public String spanId() {
                return spanId;
            }

            @Override
            public Boolean sampled() {
                return sampled;
            }
        };
    }

    @Test
    void formatsTheW3cTraceparentOfTheCurrentSpan() {
        OtelTracer tracer = TestTracing.tracer(InMemorySpanExporter.create());
        assertThat(TraceParent.current(tracer)).isNull();                       // no span

        Span span = tracer.nextSpan().name("place-order").start();
        try (Tracer.SpanInScope ignored = tracer.withSpan(span)) {
            String traceparent = TraceParent.current(tracer);
            assertThat(traceparent).isEqualTo("00-" + span.context().traceId() + "-" + span.context().spanId() + "-01")
                    .hasSize(TraceParent.LENGTH).matches("00-[0-9a-f]{32}-[0-9a-f]{16}-01");
            assertThat(TraceParent.supplier(tracer).get()).isEqualTo(traceparent);
        } finally {
            span.end();
        }
        assertThat(TraceParent.current(tracer)).isNull();
        assertThat(TraceParent.current(null)).isNull();
        assertThat(TraceParent.supplier(null).get()).isNull();
        assertThat(TraceParent.current(Tracer.NOOP)).isNull();
    }

    @Test
    void sampledFlagPaddingAndInvalidIds() {
        String traceId = "4bf92f3577b34da6a3ce929d0e0e4736";
        String spanId = "00f067aa0ba902b7";
        assertThat(TraceParent.of(context(traceId, spanId, true))).isEqualTo("00-" + traceId + "-" + spanId + "-01");
        assertThat(TraceParent.of(context(traceId, spanId, false))).isEqualTo("00-" + traceId + "-" + spanId + "-00");
        assertThat(TraceParent.of(context(traceId, spanId, null))).endsWith("-00");
        assertThat(TraceParent.of(context(traceId.toUpperCase(), spanId, true))).isEqualTo("00-" + traceId + "-" + spanId + "-01");
        // 64-bit trace ids (B3) are left-padded to 32 hex digits
        assertThat(TraceParent.of(context("a3ce929d0e0e4736", spanId, true)))
                .isEqualTo("00-0000000000000000a3ce929d0e0e4736-" + spanId + "-01");
        assertThat(TraceParent.of(context("0".repeat(32), spanId, true))).isNull();
        assertThat(TraceParent.of(context(traceId, "0".repeat(16), true))).isNull();
        assertThat(TraceParent.of(context("not-hex", spanId, true))).isNull();
        assertThat(TraceParent.of(context(traceId, "abc", true))).isNull();
        assertThat(TraceParent.of(null)).isNull();
    }
}
