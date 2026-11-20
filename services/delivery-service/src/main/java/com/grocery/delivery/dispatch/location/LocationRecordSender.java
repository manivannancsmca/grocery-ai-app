package com.grocery.delivery.dispatch.location;

import org.apache.kafka.clients.producer.ProducerRecord;

/**
 * Hands a ready {@code delivery.location} record (Avro value, outbox-style headers) to Kafka. The seam tests replace
 * with a recording fake, so they need no broker.
 */
public interface LocationRecordSender {

    /** Sends asynchronously; may throw when the record cannot even be queued (the caller logs and moves on). */
    void send(ProducerRecord<String, byte[]> record);
}
