package com.grocery.common.observability;

import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;

import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * The W3C {@code traceparent} of the current span (Trace Context level 1, version {@code 00}):
 * {@code 00-<32 hex trace id>-<16 hex span id>-<01 sampled | 00 not sampled>} — always {@value #LENGTH} characters,
 * the size of the outbox column {@code traceparent VARCHAR(55)}. Debezium copies the column into the Kafka header
 * {@code traceparent}, and the consumer's listener observation continues the trace from it.
 */
public final class TraceParent {

    /** Kafka header and outbox column name. */
    public static final String HEADER = "traceparent";
    public static final int LENGTH = 55;

    private static final Pattern HEX = Pattern.compile("[0-9a-f]+");
    private static final String ZERO_TRACE_ID = "0".repeat(32);
    private static final String ZERO_SPAN_ID = "0".repeat(16);

    private TraceParent() {
    }

    /** The current span's {@code traceparent}, or {@code null} without a current span (or with tracing off). */
    public static String current(Tracer tracer) {
        if (tracer == null) {
            return null;
        }
        try {
            return of(tracer.currentTraceContext().context());
        } catch (RuntimeException e) {
            return null;                                   // tracing must never break the business call
        }
    }

    /** A supplier of {@link #current(Tracer)}; {@code null} tracer = always {@code null}. */
    public static Supplier<String> supplier(Tracer tracer) {
        return tracer == null ? () -> null : () -> current(tracer);
    }

    /** {@code traceparent} of {@code context}, or {@code null} if there is none or its ids are not valid W3C ids. */
    public static String of(TraceContext context) {
        if (context == null) {
            return null;
        }
        String traceId = lowerHex(context.traceId());
        String spanId = lowerHex(context.spanId());
        if (traceId == null || spanId == null || spanId.length() != 16 || (traceId.length() != 32 && traceId.length() != 16)) {
            return null;
        }
        if (traceId.length() == 16) {
            traceId = "0".repeat(16) + traceId;            // 64-bit trace ids (B3) are left-padded
        }
        if (traceId.equals(ZERO_TRACE_ID) || spanId.equals(ZERO_SPAN_ID)) {
            return null;
        }
        return "00-" + traceId + "-" + spanId + "-" + (Boolean.TRUE.equals(context.sampled()) ? "01" : "00");
    }

    private static String lowerHex(String id) {
        if (id == null) {
            return null;
        }
        String lower = id.trim().toLowerCase(java.util.Locale.ROOT);
        return HEX.matcher(lower).matches() ? lower : null;
    }
}
