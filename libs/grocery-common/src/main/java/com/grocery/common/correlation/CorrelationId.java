package com.grocery.common.correlation;

import org.slf4j.MDC;

import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Correlation ID conventions shared by HTTP, logging and Kafka.
 *
 * <p>One business transaction (e.g. "place order") keeps the same correlation ID across every
 * service it touches, so its logs and events can be found with a single search.
 */
public final class CorrelationId {

    /** HTTP header used by the gateway, the services and the React client. */
    public static final String HEADER = "X-Correlation-Id";
    /** Logging MDC key (appears automatically in Spring Boot structured JSON logs). */
    public static final String MDC_KEY = "correlationId";
    /** MDC key for the ID of the event that caused the current work (set by Kafka consumers). */
    public static final String CAUSATION_MDC_KEY = "causationId";

    private static final Pattern SAFE_VALUE = Pattern.compile("^[A-Za-z0-9._-]{8,64}$");

    private CorrelationId() {
    }

    /** Returns the incoming value if it is safe to log and propagate, otherwise {@code null}. */
    public static String sanitize(String candidate) {
        if (candidate == null) {
            return null;
        }
        String trimmed = candidate.trim();
        return SAFE_VALUE.matcher(trimmed).matches() ? trimmed : null;
    }

    public static String newValue() {
        return UUID.randomUUID().toString();
    }

    /** The correlation ID of the current thread's work, or {@code null} when none is set. */
    public static String current() {
        return MDC.get(MDC_KEY);
    }

    /** The ID of the event currently being processed, or {@code null}. */
    public static String currentCausation() {
        return MDC.get(CAUSATION_MDC_KEY);
    }
}
