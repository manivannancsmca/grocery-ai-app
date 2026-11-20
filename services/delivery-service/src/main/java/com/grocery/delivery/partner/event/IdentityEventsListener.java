package com.grocery.delivery.partner.event;

import com.grocery.common.kafka.EventEnvelope;
import com.grocery.common.security.Roles;
import com.grocery.delivery.partner.service.PartnerOnboardingService;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.util.UUID;

/** identity.events → a DRAFT partner profile for every user who registers as DELIVERY_PARTNER. */
@Component
public class IdentityEventsListener {

    /** Our own view of UserRegistered: only the fields this service needs. */
    record UserRegistered(UUID userId, String email, String fullName, String phone, String role) {
    }

    private final PartnerOnboardingService onboarding;
    private final JsonMapper jsonMapper;

    public IdentityEventsListener(PartnerOnboardingService onboarding, JsonMapper jsonMapper) {
        this.onboarding = onboarding;
        this.jsonMapper = jsonMapper;
    }

    @KafkaListener(id = "delivery-identity-events", groupId = PartnerOnboardingService.IDENTITY_CONSUMER,
            topics = "identity.events")
    public void on(ConsumerRecord<String, String> record) {
        EventEnvelope event = EventEnvelope.from(record);
        if (!"UserRegistered".equals(event.eventType())) {
            return;
        }
        UserRegistered user = event.payloadAs(jsonMapper, UserRegistered.class);
        if (Roles.DELIVERY_PARTNER.equals(user.role())) {
            onboarding.onDeliveryPartnerRegistered(event.eventId(), user.userId(), user.fullName(), user.email(),
                    user.phone());
        }
    }
}
