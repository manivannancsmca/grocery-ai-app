package com.grocery.reporting.event;

import com.grocery.common.event.EventHeaders;
import com.grocery.common.kafka.EventEnvelope;
import com.grocery.reporting.facts.FactsService;
import com.grocery.reporting.facts.FactsService.Outcome;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * The only consumer of this service: group {@value FactsService#CONSUMER} over the five topics of contract §7. Values
 * arrive as JSON through grocery-common's Avro deserializer. Failures follow grocery-common's error handler:
 * {@code InvalidEventException} (unreadable payload, missing headers or ids) → {@code <topic>.DLT} at once; transient
 * failures (database down, deadlocks, lock timeouts) → the same record is retried. Event types this service does not
 * use are skipped.
 */
@Component
public class FactsListener {

    private final FactsService facts;

    public FactsListener(FactsService facts) {
        this.facts = facts;
    }

    @KafkaListener(id = "reporting-facts", groupId = FactsService.CONSUMER, topics = {
            "order.events", "payment.events", "delivery.events", "review.events", "vendor.events"})
    public void on(ConsumerRecord<String, String> record) {
        handle(record);
    }

    /** What the record did (the listener itself returns nothing, so no reply is ever sent). */
    public Outcome handle(ConsumerRecord<String, String> record) {
        EventEnvelope event = EventEnvelope.from(record);
        return facts.apply(event, OccurredAt.parse(EventEnvelope.header(record, EventHeaders.OCCURRED_AT)));
    }
}
