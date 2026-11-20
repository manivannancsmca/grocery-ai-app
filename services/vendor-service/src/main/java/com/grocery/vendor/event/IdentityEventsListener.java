package com.grocery.vendor.event;

import com.grocery.common.kafka.EventEnvelope;
import com.grocery.common.security.Roles;
import com.grocery.vendor.service.VendorOnboardingService;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.util.UUID;

/** identity.events → a DRAFT vendor profile for every user who registers as VENDOR. */
@Component
public class IdentityEventsListener {

    /** Our own view of UserRegistered: only the fields this service needs. */
    record UserRegistered(UUID userId, String email, String fullName, String phone, String role) {
    }

    private final VendorOnboardingService onboarding;
    private final JsonMapper jsonMapper;

    public IdentityEventsListener(VendorOnboardingService onboarding, JsonMapper jsonMapper) {
        this.onboarding = onboarding;
        this.jsonMapper = jsonMapper;
    }

    @KafkaListener(id = "vendor-identity-events", groupId = VendorOnboardingService.IDENTITY_CONSUMER,
            topics = "identity.events")
    public void on(ConsumerRecord<String, String> record) {
        EventEnvelope event = EventEnvelope.from(record);
        if (!"UserRegistered".equals(event.eventType())) {
            return;
        }
        UserRegistered user = event.payloadAs(jsonMapper, UserRegistered.class);
        if (Roles.VENDOR.equals(user.role())) {
            onboarding.onVendorUserRegistered(event.eventId(), user.userId(), user.fullName(), user.email(), user.phone());
        }
    }
}
