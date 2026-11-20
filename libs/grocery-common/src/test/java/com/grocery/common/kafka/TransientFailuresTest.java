package com.grocery.common.kafka;

import io.confluent.kafka.schemaregistry.client.rest.exceptions.RestClientException;
import org.apache.kafka.common.errors.SerializationException;
import org.apache.kafka.common.errors.ThrottlingQuotaExceededException;
import org.apache.kafka.common.errors.TimeoutException;
import org.junit.jupiter.api.Test;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.kafka.listener.ListenerExecutionFailedException;

import java.io.UncheckedIOException;
import java.net.ConnectException;

import static org.assertj.core.api.Assertions.assertThat;

class TransientFailuresTest {

    @Test
    void outagesAreTransientEvenWhenWrappedByTheListenerContainer() {
        assertThat(TransientFailures.isTransient(new ListenerExecutionFailedException("listener failed",
                new UncheckedIOException(new ConnectException("Connection refused"))))).isTrue();
        assertThat(TransientFailures.isTransient(new DataAccessResourceFailureException("db down"))).isTrue();
        assertThat(TransientFailures.isTransient(new CannotAcquireLockException("lock wait timeout"))).isTrue();
        assertThat(TransientFailures.isTransient(new TransientEventProcessingException("search cluster busy"))).isTrue();
    }

    @Test
    void processingErrorsAndPoisonMessagesAreNotTransient() {
        assertThat(TransientFailures.isTransient(new IllegalStateException("bug"))).isFalse();
        assertThat(TransientFailures.isTransient(new DataIntegrityViolationException("duplicate key"))).isFalse();
        assertThat(TransientFailures.isTransient(new InvalidEventException("missing eventId",
                new UncheckedIOException(new ConnectException("irrelevant"))))).isFalse();
    }

    @Test
    void schemaRegistryOutagesAreTransientUnknownSchemasAreNot() {
        for (int status : new int[]{500, 502, 503, 504, 408, 429}) {
            assertThat(TransientFailures.isTransient(new IllegalStateException("encode failed",
                    new SerializationException("Error retrieving Avro schema", new RestClientException("x", status, status * 100)))))
                    .as("HTTP %d", status).isTrue();
        }
        for (int status : new int[]{400, 401, 403, 404, 409, 422}) {
            assertThat(TransientFailures.isTransient(new IllegalStateException("encode failed",
                    new SerializationException("Error retrieving Avro schema", new RestClientException("x", status, status * 100)))))
                    .as("HTTP %d", status).isFalse();
        }
        // Confluent maps 5xx/408 to TimeoutException and 429 to ThrottlingQuotaExceededException (without cause)
        assertThat(TransientFailures.isTransient(new TimeoutException("registry timeout"))).isTrue();
        assertThat(TransientFailures.isTransient(new ThrottlingQuotaExceededException("throttled"))).isTrue();
        // registry I/O failure
        assertThat(TransientFailures.isTransient(new SerializationException("Error retrieving Avro schema",
                new ConnectException("Connection refused")))).isTrue();
        assertThat(TransientFailures.isTransientRegistryStatus(503)).isTrue();
        assertThat(TransientFailures.isTransientRegistryStatus(404)).isFalse();
    }
}
