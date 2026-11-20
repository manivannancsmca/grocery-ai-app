package com.grocery.customer.event;

import com.grocery.common.kafka.EventEnvelope;
import com.grocery.common.security.Roles;
import com.grocery.customer.service.CustomerProfileService;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.util.UUID;

/** identity.events → a customer profile for every user who registers as CUSTOMER. */
@Component
public class IdentityEventsListener {

    /** Our own view of UserRegistered: only the fields this service needs. */
    record UserRegistered(UUID userId, String email, String fullName, String phone, String role) {
    }

    private final CustomerProfileService profiles;
    private final JsonMapper jsonMapper;

    public IdentityEventsListener(CustomerProfileService profiles, JsonMapper jsonMapper) {
        this.profiles = profiles;
        this.jsonMapper = jsonMapper;
    }

    @KafkaListener(id = "customer-identity-events", groupId = CustomerProfileService.IDENTITY_CONSUMER,
            topics = "identity.events")
    public void on(ConsumerRecord<String, String> record) {
        EventEnvelope event = EventEnvelope.from(record);
        if (!"UserRegistered".equals(event.eventType())) {
            return;
        }
        UserRegistered user = event.payloadAs(jsonMapper, UserRegistered.class);
        if (Roles.CUSTOMER.equals(user.role())) {
            profiles.onCustomerUserRegistered(event.eventId(), user.userId(), user.fullName(), user.email(), user.phone());
        }
    }
}
