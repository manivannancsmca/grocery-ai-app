package com.grocery.restaurant.event;

import com.grocery.common.kafka.EventEnvelope;
import com.grocery.common.security.Roles;
import com.grocery.restaurant.service.RestaurantOnboardingService;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.util.UUID;

/** identity.events → a DRAFT restaurant profile for every user who registers as RESTAURANT_OWNER. */
@Component
public class IdentityEventsListener {

    /** Our own view of UserRegistered: only the fields this service needs. */
    record UserRegistered(UUID userId, String email, String fullName, String phone, String role) {
    }

    private final RestaurantOnboardingService onboarding;
    private final JsonMapper jsonMapper;

    public IdentityEventsListener(RestaurantOnboardingService onboarding, JsonMapper jsonMapper) {
        this.onboarding = onboarding;
        this.jsonMapper = jsonMapper;
    }

    @KafkaListener(id = "restaurant-identity-events", groupId = RestaurantOnboardingService.IDENTITY_CONSUMER,
            topics = "identity.events")
    public void on(ConsumerRecord<String, String> record) {
        EventEnvelope event = EventEnvelope.from(record);
        if (!"UserRegistered".equals(event.eventType())) {
            return;
        }
        UserRegistered user = event.payloadAs(jsonMapper, UserRegistered.class);
        if (Roles.RESTAURANT_OWNER.equals(user.role())) {
            onboarding.onRestaurantOwnerRegistered(event.eventId(), user.userId(), user.fullName(), user.email(),
                    user.phone());
        }
    }
}
