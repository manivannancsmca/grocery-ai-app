package com.grocery.order.event;

import com.grocery.order.event.StoreOrderEvents.Store;
import com.grocery.order.service.OrderEventService;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * restaurant.events → orders (consumer group {@code order-service.restaurant-events}, Phase 8): the restaurant's
 * decisions on its RESTAURANT orders — {@code RestaurantOrderAccepted}, {@code RestaurantOrderPreparing},
 * {@code RestaurantOrderReadyForPickup}, {@code RestaurantOrderRejected}. {@code RestaurantOrderReceived}, the
 * restaurant lifecycle events and {@code RestaurantListingUpdated} are skipped without touching the database. See
 * {@link StoreOrderEvents}.
 */
@Component
public class RestaurantEventsListener {

    static final String TOPIC = "restaurant.events";

    private final StoreOrderEvents storeEvents;

    public RestaurantEventsListener(StoreOrderEvents storeEvents) {
        this.storeEvents = storeEvents;
    }

    @KafkaListener(id = "order-restaurant-events", groupId = OrderEventService.RESTAURANT_CONSUMER, topics = TOPIC)
    public void on(ConsumerRecord<String, String> record) {
        storeEvents.on(record, Store.RESTAURANT);
    }
}
