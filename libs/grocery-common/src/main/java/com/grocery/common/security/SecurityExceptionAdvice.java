package com.grocery.common.security;

import com.grocery.common.correlation.CorrelationId;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Security exceptions thrown inside controllers (e.g. {@code @PreAuthorize}) — handled before the
 * generic handler so they become 403/401 instead of 500.
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class SecurityExceptionAdvice {

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ProblemDetail> accessDenied(AccessDeniedException ex) {
        return problem(HttpStatus.FORBIDDEN, "You are not allowed to perform this operation.", "FORBIDDEN");
    }

    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ProblemDetail> unauthenticated(AuthenticationException ex) {
        return problem(HttpStatus.UNAUTHORIZED, "Authentication is required.", "UNAUTHENTICATED");
    }

    private static ResponseEntity<ProblemDetail> problem(HttpStatus status, String detail, String errorCode) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setProperty("errorCode", errorCode);
        if (CorrelationId.current() != null) {
            problem.setProperty("correlationId", CorrelationId.current());
        }
        return ResponseEntity.status(status).body(problem);
    }
}
