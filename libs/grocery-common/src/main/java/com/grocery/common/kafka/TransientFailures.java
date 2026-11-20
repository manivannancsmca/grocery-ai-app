package com.grocery.common.kafka;

import java.io.IOException;
import java.lang.reflect.Method;
import java.util.Set;

/**
 * Decides whether a consumer failure is an <b>outage</b> (retry the same record until the dependency is
 * back) or a <b>processing error</b> (a few retries, then the dead-letter topic).
 *
 * <p>Classes are matched by name along the cause chain and class hierarchy, so this works in services
 * that have no JDBC, no Spring transaction module or no Schema Registry client on the classpath
 * (e.g. the search service).
 *
 * <p>Schema Registry (0.6.0): a Confluent {@code RestClientException} is an outage when its HTTP status is 5xx, 408
 * (timeout) or 429 (throttled); any other 4xx (404 unknown schema / subject, 422 invalid schema, 401/403
 * credentials) is not. Registry I/O failures are {@link IOException}s, and Kafka's {@code RetriableException}s
 * (Confluent maps 5xx/408/429 to {@code TimeoutException} / {@code ThrottlingQuotaExceededException}) are outages too.
 */
public final class TransientFailures {

    /** Infrastructure exceptions (and their subclasses) that mean "try again later". */
    private static final Set<String> TRANSIENT_TYPES = Set.of(
            "org.springframework.dao.TransientDataAccessException",       // lock timeouts, deadlocks, optimistic locking
            "org.springframework.dao.RecoverableDataAccessException",
            "org.springframework.dao.DataAccessResourceFailureException",  // database unreachable
            "org.springframework.transaction.CannotCreateTransactionException",
            "java.sql.SQLTransientException",
            "java.sql.SQLRecoverableException",
            "org.apache.kafka.common.errors.RetriableException");          // broker / registry timeouts, throttling

    private static final String REST_CLIENT_EXCEPTION =
            "io.confluent.kafka.schemaregistry.client.rest.exceptions.RestClientException";

    private static final int MAX_CAUSE_DEPTH = 15;

    private TransientFailures() {
    }

    public static boolean isTransient(Throwable failure) {
        Throwable current = failure;
        for (int depth = 0; current != null && depth < MAX_CAUSE_DEPTH; depth++) {
            if (current instanceof InvalidEventException) {
                return false;                   // a poison message never becomes valid by waiting
            }
            if (current instanceof TransientEventProcessingException || current instanceof IOException) {
                return true;                    // includes ConnectException, SocketTimeoutException, HTTP client I/O
            }
            if (isSubclassOf(current.getClass(), Set.of(REST_CLIENT_EXCEPTION))) {
                Integer status = httpStatus(current);
                return status != null && isTransientRegistryStatus(status);
            }
            if (isSubclassOf(current.getClass(), TRANSIENT_TYPES)) {
                return true;
            }
            current = current.getCause() == current ? null : current.getCause();
        }
        return false;
    }

    /** A Schema Registry answer worth retrying: server errors (5xx), request timeout (408), throttling (429). */
    public static boolean isTransientRegistryStatus(int httpStatus) {
        return httpStatus >= 500 || httpStatus == 408 || httpStatus == 429;
    }

    private static boolean isSubclassOf(Class<?> type, Set<String> names) {
        for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
            if (names.contains(c.getName())) {
                return true;
            }
        }
        return false;
    }

    /** {@code RestClientException.getStatus()}, read reflectively so this class never needs Confluent's jar. */
    private static Integer httpStatus(Throwable restClientException) {
        try {
            Method getStatus = restClientException.getClass().getMethod("getStatus");
            return (Integer) getStatus.invoke(restClientException);
        } catch (ReflectiveOperationException | ClassCastException e) {
            return null;
        }
    }
}
