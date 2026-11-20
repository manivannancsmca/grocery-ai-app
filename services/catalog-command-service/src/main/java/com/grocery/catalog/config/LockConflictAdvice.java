package com.grocery.catalog.config;

import com.grocery.common.correlation.CorrelationId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Snapshot transactions take row locks (see {@code SnapshotPublisher}). When two of them collide on the
 * same rows in opposite order InnoDB aborts one (deadlock) or a lock wait times out; the client gets a
 * 409 and can simply retry, instead of a 500. (A candidate for grocery-common's DataAccessExceptionAdvice.)
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class LockConflictAdvice {

    private static final Logger log = LoggerFactory.getLogger(LockConflictAdvice.class);

    @ExceptionHandler(PessimisticLockingFailureException.class)
    public ResponseEntity<ProblemDetail> lockConflict(PessimisticLockingFailureException ex) {
        log.warn("Lock conflict: {}", ex.getMostSpecificCause().getMessage());
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT,
                "The resource is being changed by someone else. Try again.");
        problem.setProperty("errorCode", "CONCURRENT_MODIFICATION");
        if (CorrelationId.current() != null) {
            problem.setProperty("correlationId", CorrelationId.current());
        }
        return ResponseEntity.status(HttpStatus.CONFLICT).body(problem);
    }
}
