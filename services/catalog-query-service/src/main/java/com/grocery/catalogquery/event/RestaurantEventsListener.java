package com.grocery.catalogquery.event;

import com.grocery.catalogquery.event.EventPayloads.RestaurantListingUpdated;
import com.grocery.catalogquery.service.RestaurantIndexer;
import com.grocery.common.kafka.EventEnvelope;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * restaurant.events → restaurants index (group {@code catalog-query.restaurant-indexer}). Only the listing
 * snapshot matters: lifecycle events (RestaurantApproved, RestaurantSuspended, …) are always followed by a
 * RestaurantListingUpdated carrying the new status, so they are ignored here.
 */
@Component
public class RestaurantEventsListener {

    public static final String GROUP = "catalog-query.restaurant-indexer";
    public static final String TOPIC = "restaurant.events";

    private final RestaurantIndexer indexer;
    private final JsonMapper jsonMapper;

    public RestaurantEventsListener(RestaurantIndexer indexer, JsonMapper jsonMapper) {
        this.indexer = indexer;
        this.jsonMapper = jsonMapper;
    }

    @KafkaListener(id = "catalog-query-restaurant-indexer", groupId = GROUP, topics = TOPIC)
    public void on(ConsumerRecord<String, String> record) {
        EventEnvelope event = EventEnvelope.from(record);
        if ("RestaurantListingUpdated".equals(event.eventType())) {
            indexer.onListingUpdated(event.payloadAs(jsonMapper, RestaurantListingUpdated.class));
        }
    }
}
