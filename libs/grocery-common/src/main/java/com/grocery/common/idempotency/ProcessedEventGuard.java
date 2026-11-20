package com.grocery.common.idempotency;

/**
 * Makes Kafka consumers idempotent. Kafka delivers "at least once", so the same event can arrive
 * twice (retries, rebalances, replays). Call this as the first step of the consumer's transaction:
 *
 * <pre>{@code
 * @Transactional
 * void on(OrderCreated event, String eventId) {
 *     if (!guard.markProcessed(eventId, "inventory-service.order-events")) {
 *         return; // duplicate — already handled
 *     }
 *     ... business change (same transaction) ...
 * }
 * }</pre>
 *
 * If the business change fails, the transaction rolls back and the marker disappears with it,
 * so a retry will process the event again.
 *
 * <p>Metrics (0.9.0): implementations report each answer with
 * {@link com.grocery.common.observability.ConsumedEvents#report(boolean)}, so the record is counted as
 * {@code APPLIED} or {@code DUPLICATE} in {@code grocery_events_consumed_total} ({@link JdbcProcessedEventGuard} does).
 */
public interface ProcessedEventGuard {

    /**
     * @return {@code true} the first time this (eventId, consumer) pair is seen, {@code false} for duplicates
     * @throws IllegalStateException if no transaction is active
     */
    boolean markProcessed(String eventId, String consumer);
}
