package com.grocery.delivery.dispatch.controller;

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
 * On the dispatch endpoints a path or query parameter of the wrong type (an id that is not a UUID,
 * {@code assignmentFailed=maybe}, {@code page=abc}) is 400 INVALID_PARAMETER — the same code as an unknown
 * {@code status}. Every other error is rendered by grocery-common (validation, ApiExceptions, lock conflicts, 401/403).
 */
@RestControllerAdvice(assignableTypes = {PartnerDispatchController.class, TrackingController.class,
        AdminDeliveryController.class})
@Order(Ordered.HIGHEST_PRECEDENCE)
public class DispatchParameterAdvice {

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
