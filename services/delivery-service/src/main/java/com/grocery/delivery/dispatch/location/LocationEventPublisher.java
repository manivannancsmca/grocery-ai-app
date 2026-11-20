package com.grocery.delivery.dispatch.location;

import com.grocery.common.avro.AvroEventEncoder;
import com.grocery.common.correlation.CorrelationId;
import com.grocery.common.event.EventHeaders;
import com.grocery.common.id.Ids;
import com.grocery.delivery.dispatch.event.DeliveryEvents;
import com.grocery.delivery.dispatch.event.DeliveryEvents.DeliveryLocationUpdated;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.Headers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * Publishes {@code DeliveryLocationUpdated} on {@code delivery.location} directly (contract §6: no outbox, key
 * orderId, best effort). The value is the payload in the Confluent wire format, encoded with grocery-common's
 * {@link AvroEventEncoder} against the central schema exactly as the outbox would; the headers are the ones Debezium's
 * outbox router sets ({@code eventId, eventType, eventVersion, aggregateType, correlationId, occurredAt} — occurredAt
 * in epoch microseconds), so consumers read it like any other event. Every failure (encoding, registry, Kafka) is
 * logged and swallowed: a lost position is replaced by the next one 2 s later, and the partner's HTTP call never
 * fails because of it. With Avro switched off ({@code grocery.events.avro.enabled=false}) the value is the JSON.
 */
@Component
public class LocationEventPublisher {

    static final int EVENT_VERSION = 1;
    private static final Logger log = LoggerFactory.getLogger(LocationEventPublisher.class);

    private final LocationRecordSender sender;
    private final ObjectProvider<AvroEventEncoder> avroEncoder;
    private final JsonMapper jsonMapper;

    public LocationEventPublisher(LocationRecordSender sender, ObjectProvider<AvroEventEncoder> avroEncoder,
                                  JsonMapper jsonMapper) {
        this.sender = sender;
        this.avroEncoder = avroEncoder;
        this.jsonMapper = jsonMapper;
    }

    /** Returns whether the record was handed to Kafka (false = dropped, already logged). */
    public boolean publish(DeliveryLocationUpdated event) {
        try {
            sender.send(toRecord(event));
            return true;
        } catch (RuntimeException e) {
            log.warn("DeliveryLocationUpdated for order {} (partner {}) not published: {}", event.orderId(),
                    event.partnerId(), e.toString());
            return false;
        }
    }

    ProducerRecord<String, byte[]> toRecord(DeliveryLocationUpdated event) {
        AvroEventEncoder encoder = avroEncoder.getIfAvailable();
        byte[] value;
        if (encoder != null) {
            JsonNode payload = jsonMapper.readTree(jsonMapper.writeValueAsString(event));
            value = encoder.encode(DeliveryEvents.LOCATION_TOPIC, DeliveryEvents.LOCATION_UPDATED, EVENT_VERSION,
                    payload);
        } else {
            value = jsonMapper.writeValueAsBytes(event);
        }
        String eventId = Ids.newId().toString();
        ProducerRecord<String, byte[]> record = new ProducerRecord<>(DeliveryEvents.LOCATION_TOPIC,
                event.orderId().toString(), value);
        Headers headers = record.headers();
        header(headers, EventHeaders.EVENT_ID, eventId);
        header(headers, EventHeaders.EVENT_TYPE, DeliveryEvents.LOCATION_UPDATED);
        header(headers, EventHeaders.EVENT_VERSION, Integer.toString(EVENT_VERSION));
        header(headers, EventHeaders.AGGREGATE_TYPE, DeliveryEvents.AGGREGATE);
        header(headers, EventHeaders.CORRELATION_ID,
                CorrelationId.current() != null ? CorrelationId.current() : eventId);
        header(headers, EventHeaders.OCCURRED_AT, Long.toString(epochMicros(event.recordedAt())));
        return record;
    }

    static long epochMicros(Instant at) {
        return ChronoUnit.MICROS.between(Instant.EPOCH, at);
    }

    private static void header(Headers headers, String name, String value) {
        headers.add(name, value.getBytes(StandardCharsets.UTF_8));
    }
}
