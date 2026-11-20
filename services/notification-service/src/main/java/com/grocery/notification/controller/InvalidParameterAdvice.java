package com.grocery.notification.controller;

import com.grocery.common.correlation.CorrelationId;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * A query parameter of the wrong type ({@code unread=maybe}, {@code page=x}) is 400 INVALID_PARAMETER. Every other
 * error is rendered by grocery-common: {@code ApiException}s (404 NOTIFICATION_NOT_FOUND) by its
 * GlobalExceptionHandler, 401/403 by its security handlers.
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class InvalidParameterAdvice {

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ProblemDetail> typeMismatch(MethodArgumentTypeMismatchException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST,
                "Invalid value for parameter '" + ex.getName() + "'");
        problem.setProperty("errorCode", "INVALID_PARAMETER");
        if (CorrelationId.current() != null) {
            problem.setProperty("correlationId", CorrelationId.current());
        }
        return ResponseEntity.badRequest().body(problem);
    }
}
