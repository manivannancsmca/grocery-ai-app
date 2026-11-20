package com.grocery.order.controller;

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
 * A query or path parameter of the wrong type ({@code customerId=abc}, {@code from=11-11-2026}, an order id that is
 * not a UUID) is 400 INVALID_PARAMETER — the same code as an unknown {@code status} / {@code type} value.
 * Every other error is rendered by grocery-common: {@code ApiException}s (including {@link
 * com.grocery.order.service.OrderException}) and validation by its GlobalExceptionHandler, row-lock conflicts by its
 * DataAccessExceptionAdvice (409 CONCURRENT_MODIFICATION), 401/403 by its security handlers.
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
