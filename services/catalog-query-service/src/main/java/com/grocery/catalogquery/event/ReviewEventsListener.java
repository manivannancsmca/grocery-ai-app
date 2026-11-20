package com.grocery.catalogquery.event;

import com.grocery.catalogquery.event.EventPayloads.RatingSummaryUpdated;
import com.grocery.catalogquery.service.RatingIndexer;
import com.grocery.common.kafka.EventEnvelope;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * review.events → the rating part of the product and restaurant documents (group {@code catalog-query.rating-indexer},
 * Phase 12). Idempotency comes from {@code summaryVersion}, so replaying is harmless. Reviews themselves
 * (ReviewSubmitted, ReviewModerated, ReviewReplied) are not part of the search model.
 */
@Component
public class ReviewEventsListener {

    public static final String GROUP = "catalog-query.rating-indexer";
    public static final String TOPIC = "review.events";

    private final RatingIndexer indexer;
    private final JsonMapper jsonMapper;

    public ReviewEventsListener(RatingIndexer indexer, JsonMapper jsonMapper) {
        this.indexer = indexer;
        this.jsonMapper = jsonMapper;
    }

    @KafkaListener(id = "catalog-query-rating-indexer", groupId = GROUP, topics = TOPIC)
    public void on(ConsumerRecord<String, String> record) {
        EventEnvelope event = EventEnvelope.from(record);
        if ("RatingSummaryUpdated".equals(event.eventType())) {
            indexer.onRatingSummaryUpdated(event.payloadAs(jsonMapper, RatingSummaryUpdated.class));
        }
    }
}
