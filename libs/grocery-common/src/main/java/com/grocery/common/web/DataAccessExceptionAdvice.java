package com.grocery.common.web;

import com.grocery.common.correlation.CorrelationId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Concurrency and constraint conflicts become 409 instead of 500. Registered only when spring-tx is
 * on the classpath (services with a database).
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class DataAccessExceptionAdvice {

    private static final Logger log = LoggerFactory.getLogger(DataAccessExceptionAdvice.class);

    /** Someone else changed the same row first (@Version). The client should reload and retry. */
    @ExceptionHandler(OptimisticLockingFailureException.class)
    public ResponseEntity<ProblemDetail> optimisticLock(OptimisticLockingFailureException ex) {
        return problem("The resource was changed by someone else. Reload and try again.", "CONCURRENT_MODIFICATION");
    }

    /**
     * A row lock could not be taken in time, or the database chose this transaction as a deadlock victim
     * (busy stock row, concurrent edits). Nothing was written; retrying is safe.
     */
    @ExceptionHandler(PessimisticLockingFailureException.class)
    public ResponseEntity<ProblemDetail> pessimisticLock(PessimisticLockingFailureException ex) {
        log.warn("Lock conflict: {}", ex.getMostSpecificCause().getMessage());
        return problem("The resource is busy with another change. Please try again.", "CONCURRENT_MODIFICATION");
    }

    /** A unique/foreign-key constraint fired (usually a duplicate that slipped past a pre-check). */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ProblemDetail> integrity(DataIntegrityViolationException ex) {
        log.warn("Data integrity violation: {}", ex.getMostSpecificCause().getMessage());
        return problem("The request conflicts with existing data.", "DATA_CONFLICT");
    }

    private static ResponseEntity<ProblemDetail> problem(String detail, String errorCode) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, detail);
        problem.setProperty("errorCode", errorCode);
        if (CorrelationId.current() != null) {
            problem.setProperty("correlationId", CorrelationId.current());
        }
        return ResponseEntity.status(HttpStatus.CONFLICT).body(problem);
    }
}
