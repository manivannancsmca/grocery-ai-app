package com.grocery.restaurant.service;

import com.grocery.common.id.Ids;
import com.grocery.common.outbox.OutboxEvent;
import com.grocery.common.outbox.OutboxPublisher;
import com.grocery.restaurant.entity.Restaurant;
import com.grocery.restaurant.entity.RestaurantStatus;
import com.grocery.restaurant.entity.RestaurantStatusChange;
import com.grocery.restaurant.event.RestaurantEvents;
import com.grocery.restaurant.event.RestaurantEvents.RestaurantLifecycleEvent;
import com.grocery.restaurant.repository.RestaurantStatusChangeRepository;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.UUID;

/**
 * After every status change: one history row + one outbox event, in the caller's transaction.
 * Keeps the "audit and publish" step identical for every transition.
 */
@Component
public class RestaurantLifecycleRecorder {

    private final RestaurantStatusChangeRepository history;
    private final OutboxPublisher outbox;
    private final Clock clock;

    public RestaurantLifecycleRecorder(RestaurantStatusChangeRepository history, OutboxPublisher outbox, Clock clock) {
        this.history = history;
        this.outbox = outbox;
        this.clock = clock;
    }

    public void record(Restaurant restaurant, RestaurantStatus from, String eventType, String reason, UUID actorUserId) {
        history.save(new RestaurantStatusChange(Ids.newId(), restaurant.getId(), from, restaurant.getStatus(), reason,
                actorUserId, clock.instant()));
        outbox.publish(OutboxEvent.of(RestaurantEvents.TOPIC, RestaurantEvents.AGGREGATE, restaurant.getId().toString(),
                eventType, new RestaurantLifecycleEvent(restaurant.getId(), restaurant.getOwnerUserId(),
                        restaurant.getName(), reason, actorUserId)));
    }
}
