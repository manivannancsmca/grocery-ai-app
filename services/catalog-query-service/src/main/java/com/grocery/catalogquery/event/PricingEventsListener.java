package com.grocery.catalogquery.event;

import com.grocery.catalogquery.event.EventPayloads.PriceChanged;
import com.grocery.catalogquery.service.PriceAndStockIndexer;
import com.grocery.common.kafka.EventEnvelope;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * pricing.events → the price part of the product documents (group {@code catalog-query.price-indexer}).
 * Idempotency comes from {@code priceVersion}, so replaying is harmless.
 */
@Component
public class PricingEventsListener {

    public static final String GROUP = "catalog-query.price-indexer";
    public static final String TOPIC = "pricing.events";

    private final PriceAndStockIndexer indexer;
    private final JsonMapper jsonMapper;

    public PricingEventsListener(PriceAndStockIndexer indexer, JsonMapper jsonMapper) {
        this.indexer = indexer;
        this.jsonMapper = jsonMapper;
    }

    @KafkaListener(id = "catalog-query-price-indexer", groupId = GROUP, topics = TOPIC)
    public void on(ConsumerRecord<String, String> record) {
        EventEnvelope event = EventEnvelope.from(record);
        if ("PriceChanged".equals(event.eventType())) {
            indexer.onPriceChanged(event.payloadAs(jsonMapper, PriceChanged.class));
        }
        // Price-change requests and decisions are not part of the search model.
    }
}
