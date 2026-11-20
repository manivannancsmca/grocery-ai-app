package com.grocery.common.outbox;

import java.util.UUID;

/**
 * Writes domain events to the service's {@code outbox_event} table.
 *
 * <p>Must be called inside the same database transaction as the business change, so that the
 * change and its event commit (or roll back) together. Debezium then reads the outbox table from
 * the MySQL binlog and publishes the event to Kafka. Services never call a Kafka producer for
 * business events.
 */
public interface OutboxPublisher {

    /**
     * @return the generated event ID (also the Kafka {@code eventId} header)
     * @throws IllegalStateException if no transaction is active
     */
    UUID publish(OutboxEvent event);
}
