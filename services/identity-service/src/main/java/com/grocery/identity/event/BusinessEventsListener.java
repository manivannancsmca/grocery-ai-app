package com.grocery.identity.event;

import com.grocery.common.kafka.EventEnvelope;
import com.grocery.identity.service.BusinessMembershipService;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/** Kafka entry point: vendor and restaurant lifecycle events → business memberships (JWT claims). */
@Component
public class BusinessEventsListener {

    private final BusinessMembershipService service;

    public BusinessEventsListener(BusinessMembershipService service) {
        this.service = service;
    }

    @KafkaListener(id = "identity-business-events", groupId = BusinessMembershipService.CONSUMER,
            topics = {"vendor.events", "restaurant.events"})
    public void on(ConsumerRecord<String, String> record) {
        service.handle(EventEnvelope.from(record));
    }
}
