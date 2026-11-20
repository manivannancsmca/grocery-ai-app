package com.grocery.payment.gateway;

/**
 * The provider could not be reached or answered with a server error (timeout, connection failure, 5xx, 429, open
 * circuit breaker). The outcome of the call is unknown; it is safe to retry later.
 *
 * <p>Where it ends up:
 * <ul>
 *   <li>REST endpoints: 503 {@code PAYMENT_PROVIDER_UNAVAILABLE} (nothing is half-done: the provider is called
 *       between transactions, see the service classes);</li>
 *   <li>the {@code order.events} consumer: wrapped in grocery-common's {@code TransientEventProcessingException},
 *       so the record is retried with the outage backoff of {@code TransientFailures} instead of being
 *       dead-lettered;</li>
 *   <li>pollers and refunds: the work stays where it is (lease / {@code next_attempt_at}) and is retried later.</li>
 * </ul>
 * The cause is logged where the exception is created.
 */
public class ProviderUnavailableException extends RuntimeException {

    public ProviderUnavailableException(String message) {
        super(message);
    }
}
