package com.grocery.common.web;

import org.springframework.http.HttpStatus;

import java.util.Map;

/**
 * Base class for expected business errors. Each one is rendered as an RFC 9457
 * {@code application/problem+json} response by {@link GlobalExceptionHandler}.
 *
 * <p>{@code errorCode} is a stable, machine-readable code (e.g. {@code ORDER_ALREADY_CANCELLED})
 * that the React client can switch on; the message is for humans. Optional {@code properties}
 * are added to the problem body (e.g. {@code errors} for per-field problems).
 */
public abstract class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String errorCode;
    private final Map<String, Object> properties;

    protected ApiException(HttpStatus status, String errorCode, String message) {
        this(status, errorCode, message, Map.of());
    }

    protected ApiException(HttpStatus status, String errorCode, String message, Map<String, Object> properties) {
        super(message);
        this.status = status;
        this.errorCode = errorCode;
        this.properties = Map.copyOf(properties);
    }

    public HttpStatus status() {
        return status;
    }

    public String errorCode() {
        return errorCode;
    }

    public Map<String, Object> properties() {
        return properties;
    }
}
