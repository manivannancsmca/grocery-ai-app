package com.grocery.reporting.event;

import com.grocery.common.event.EventHeaders;
import com.grocery.common.kafka.EventEnvelope;
import com.grocery.reporting.audit.AuditService;
import com.grocery.reporting.audit.AuditService.Outcome;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * The audit trail's consumer: group {@value AuditService#CONSUMER} over the ten topics whose events name who acted
 * (README "Audit trail"), separate from the facts so neither can hold the other up. From the beginning on a new group:
 * the trail starts with every event still kept in Kafka. Failures follow grocery-common's error handler, as for the
 * facts; events that are not audit entries are skipped without a database write.
 */
@Component
public class AuditListener {

    private final AuditService audit;

    public AuditListener(AuditService audit) {
        this.audit = audit;
    }

    @KafkaListener(id = "reporting-audit", groupId = AuditService.CONSUMER, topics = {
            "identity.events", "vendor.events", "restaurant.events", "delivery.events", "pricing.events",
            "inventory.events", "order.events", "payment.events", "review.events", "ai.events"})
    public void on(ConsumerRecord<String, String> record) {
        handle(record);
    }

    public Outcome handle(ConsumerRecord<String, String> record) {
        EventEnvelope event = EventEnvelope.from(record);
        return audit.apply(event, OccurredAt.parse(EventEnvelope.header(record, EventHeaders.OCCURRED_AT)));
    }
}
