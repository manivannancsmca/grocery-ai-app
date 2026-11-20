package com.grocery.common.kafka;

/**
 * Processing failed because a dependency is temporarily unavailable (search cluster busy, broker or
 * database restarting). Throw it — or let a recognised infrastructure exception propagate — and the
 * consumer keeps retrying the SAME record with a long backoff instead of dead-lettering it, so an
 * outage never turns into lost or out-of-order updates. See {@link TransientFailures}.
 */
public class TransientEventProcessingException extends RuntimeException {

    public TransientEventProcessingException(String message) {
        super(message);
    }

    public TransientEventProcessingException(String message, Throwable cause) {
        super(message, cause);
    }
}
