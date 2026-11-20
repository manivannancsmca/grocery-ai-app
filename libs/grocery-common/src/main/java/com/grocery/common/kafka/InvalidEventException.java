package com.grocery.common.kafka;

/**
 * An event that can never be processed (missing headers, unreadable payload). It is NOT retried:
 * the error handler sends it straight to the dead-letter topic ({@code <topic>.DLT}).
 */
public class InvalidEventException extends RuntimeException {

    public InvalidEventException(String message) {
        super(message);
    }

    public InvalidEventException(String message, Throwable cause) {
        super(message, cause);
    }
}
