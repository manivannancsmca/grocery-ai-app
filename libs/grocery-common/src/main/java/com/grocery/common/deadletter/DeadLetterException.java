package com.grocery.common.deadletter;

import com.grocery.common.web.ApiException;
import org.springframework.http.HttpStatus;

import java.util.List;
import java.util.Map;

/**
 * The errors of the dead-letter API, rendered as ProblemDetail by grocery-common's {@code GlobalExceptionHandler}.
 * A conflict carries the dead letter's state as {@code currentStatus} (never a second {@code status}: that is the
 * ProblemDetail's HTTP status).
 */
public class DeadLetterException extends ApiException {

    private DeadLetterException(HttpStatus status, String errorCode, String message, Map<String, Object> properties) {
        super(status, errorCode, message, properties);
    }

    /** 404 DEAD_LETTER_NOT_FOUND. */
    public static DeadLetterException notFound(String id) {
        return new DeadLetterException(HttpStatus.NOT_FOUND, "DEAD_LETTER_NOT_FOUND",
                "Dead letter " + id + " not found in this service", Map.of());
    }

    /** 409 DEAD_LETTER_CLOSED with {@code currentStatus} (REPLAYED or DISCARDED). */
    public static DeadLetterException closed(String id, DeadLetterStatus status) {
        return new DeadLetterException(HttpStatus.CONFLICT, "DEAD_LETTER_CLOSED",
                "Dead letter " + id + " is already " + status, Map.of("currentStatus", status.name()));
    }

    /** 400 VALIDATION_FAILED with {@code errors: [{field, message}]}, the shape of bean validation errors. */
    public static DeadLetterException invalid(String field, String message) {
        return new DeadLetterException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Request validation failed.",
                Map.of("errors", List.of(Map.of("field", field, "message", message))));
    }

    /** 503 DEAD_LETTER_STATUS_NOT_SAVED: the state could not be written to Kafka. */
    public static DeadLetterException statusNotSaved(String detail, DeadLetterStatus currentStatus) {
        return new DeadLetterException(HttpStatus.SERVICE_UNAVAILABLE, "DEAD_LETTER_STATUS_NOT_SAVED", detail,
                currentStatus == null ? Map.of() : Map.of("currentStatus", currentStatus.name()));
    }

    /** 401 UNAUTHENTICATED (normally answered by the security filter chain before reaching the API). */
    public static DeadLetterException unauthenticated() {
        return new DeadLetterException(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "Authentication is required.", Map.of());
    }
}
