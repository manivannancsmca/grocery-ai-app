package com.grocery.identity.event;

import com.grocery.common.kafka.EventEnvelope;
import com.grocery.identity.service.CustomerProfileSyncService;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/** Kafka entry point: a customer's profile name and phone → their account (the header, the JWT name claim). */
@Component
public class CustomerEventsListener {

    private final CustomerProfileSyncService service;

    public CustomerEventsListener(CustomerProfileSyncService service) {
        this.service = service;
    }

    @KafkaListener(id = "identity-customer-events", groupId = CustomerProfileSyncService.CONSUMER,
            topics = "customer.events")
    public void on(ConsumerRecord<String, String> record) {
        service.handle(EventEnvelope.from(record));
    }
}
