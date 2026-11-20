package com.grocery.common.event;

/**
 * Kafka header names carried by every domain event.
 *
 * <p>Debezium's Outbox Event Router copies these from outbox columns into Kafka headers
 * (see each service's {@code deploy/debezium-connector.json}). The message key is the aggregate
 * ID, which keeps all events of one aggregate (e.g. one order) in order on one partition.
 */
public final class EventHeaders {

    public static final String EVENT_ID = "eventId";
    public static final String EVENT_TYPE = "eventType";
    public static final String EVENT_VERSION = "eventVersion";
    public static final String AGGREGATE_TYPE = "aggregateType";
    public static final String CORRELATION_ID = "correlationId";
    public static final String CAUSATION_ID = "causationId";
    /** Epoch microseconds (Debezium's representation of a DATETIME(6) column). */
    public static final String OCCURRED_AT = "occurredAt";

    private EventHeaders() {
    }
}
