package com.grocery.common.web;

import org.junit.jupiter.api.Test;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

class DataAccessExceptionAdviceTest {

    private final DataAccessExceptionAdvice advice = new DataAccessExceptionAdvice();

    @Test
    void lockTimeoutsAndDeadlocksAreRetryableConflicts() {
        ResponseEntity<ProblemDetail> response = advice.pessimisticLock(new CannotAcquireLockException("Lock wait timeout exceeded"));
        assertThat(response.getStatusCode().value()).isEqualTo(409);
        assertThat(response.getBody().getProperties()).containsEntry("errorCode", "CONCURRENT_MODIFICATION");
    }

    @Test
    void optimisticLockAndConstraintViolationsAreConflicts() {
        assertThat(advice.optimisticLock(new OptimisticLockingFailureException("stale")).getBody().getProperties())
                .containsEntry("errorCode", "CONCURRENT_MODIFICATION");
        assertThat(advice.integrity(new DataIntegrityViolationException("duplicate")).getBody().getProperties())
                .containsEntry("errorCode", "DATA_CONFLICT");
    }
}
