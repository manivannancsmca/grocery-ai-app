package com.grocery.restaurant.service;

import com.grocery.common.id.Ids;
import com.grocery.common.idempotency.ProcessedEventGuard;
import com.grocery.common.security.CurrentUser;
import com.grocery.common.web.NotFoundException;
import com.grocery.restaurant.dto.RestaurantDtos.RestaurantUpdateRequest;
import com.grocery.restaurant.dto.RestaurantDtos.RestaurantView;
import com.grocery.restaurant.entity.Restaurant;
import com.grocery.restaurant.entity.RestaurantStatus;
import com.grocery.restaurant.event.RestaurantEvents;
import com.grocery.restaurant.mapper.RestaurantMapper;
import com.grocery.restaurant.repository.RestaurantRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

/** The owner's own onboarding: draft creation, editing, submitting for approval. */
@Service
public class RestaurantOnboardingService {

    public static final String IDENTITY_CONSUMER = "restaurant-service.identity-events";
    private static final Logger log = LoggerFactory.getLogger(RestaurantOnboardingService.class);

    private final RestaurantRepository restaurants;
    private final RestaurantLifecycleRecorder recorder;
    private final ProcessedEventGuard processedEvents;
    private final Clock clock;

    public RestaurantOnboardingService(RestaurantRepository restaurants, RestaurantLifecycleRecorder recorder,
                                       ProcessedEventGuard processedEvents, Clock clock) {
        this.restaurants = restaurants;
        this.recorder = recorder;
        this.processedEvents = processedEvents;
        this.clock = clock;
    }

    /** UserRegistered(role=RESTAURANT_OWNER) → DRAFT restaurant named after the owner (idempotent). */
    @Transactional
    public void onRestaurantOwnerRegistered(String eventId, UUID userId, String fullName, String email, String phone) {
        if (!processedEvents.markProcessed(eventId, IDENTITY_CONSUMER)) {
            return;
        }
        if (restaurants.existsByOwnerUserId(userId)) {
            return; // already created on demand by the owner's first save
        }
        createDraft(userId, fullName, email, phone);
        log.info("Created draft restaurant profile for user {}", userId);
    }

    @Transactional(readOnly = true)
    public RestaurantView myProfile(CurrentUser user) {
        return RestaurantMapper.toView(restaurants.findByOwnerUserId(user.userId())
                .orElseThrow(RestaurantOnboardingService::notFound));
    }

    /**
     * Saves the draft. If the registration event has not arrived yet the profile is created here,
     * so onboarding never depends on event timing.
     */
    @Transactional
    public RestaurantView updateMyProfile(CurrentUser user, RestaurantUpdateRequest request) {
        Restaurant restaurant = restaurants.findByOwnerUserId(user.userId())
                .orElseGet(() -> createDraft(user.userId(), user.name(), user.email(), null));
        restaurant.updateDetails(RestaurantMapper.trim(request.name()), RestaurantMapper.trim(request.description()),
                RestaurantMapper.toCuisines(request.cuisines()), RestaurantMapper.trim(request.fssaiLicense()),
                RestaurantMapper.trim(request.gstin()), RestaurantMapper.trim(request.contactEmail()),
                RestaurantMapper.trim(request.contactPhone()), RestaurantMapper.toEntity(request.address()),
                RestaurantMapper.toEntities(request.openingHours()), clock.instant());
        return RestaurantMapper.toView(restaurant);
    }

    @Transactional
    public RestaurantView submitMyProfile(CurrentUser user) {
        Restaurant restaurant = restaurants.findByOwnerUserId(user.userId())
                .orElseThrow(RestaurantOnboardingService::notFound);
        RestaurantStatus from = restaurant.submit(clock.instant());
        recorder.record(restaurant, from, RestaurantEvents.SUBMITTED, null, user.userId());
        return RestaurantMapper.toView(restaurant);
    }

    private Restaurant createDraft(UUID userId, String name, String email, String phone) {
        Instant now = clock.instant();
        Restaurant restaurant = restaurants.saveAndFlush(Restaurant.draft(Ids.newId(), userId, name, email, phone, now));
        recorder.record(restaurant, null, RestaurantEvents.REGISTERED, null, userId);
        return restaurant;
    }

    static NotFoundException notFound() {
        return new NotFoundException("RESTAURANT_NOT_FOUND", "Restaurant profile not found");
    }
}
