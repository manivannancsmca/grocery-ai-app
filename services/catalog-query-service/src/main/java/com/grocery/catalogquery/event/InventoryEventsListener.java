package com.grocery.catalogquery.event;

import com.grocery.catalogquery.event.EventPayloads.ProductAvailabilityChanged;
import com.grocery.catalogquery.service.PriceAndStockIndexer;
import com.grocery.common.kafka.EventEnvelope;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * inventory.events → the stock part of the product documents (group {@code catalog-query.stock-indexer}).
 * Idempotency comes from {@code stockVersion}, so replaying is harmless.
 */
@Component
public class InventoryEventsListener {

    public static final String GROUP = "catalog-query.stock-indexer";
    public static final String TOPIC = "inventory.events";

    private final PriceAndStockIndexer indexer;
    private final JsonMapper jsonMapper;

    public InventoryEventsListener(PriceAndStockIndexer indexer, JsonMapper jsonMapper) {
        this.indexer = indexer;
        this.jsonMapper = jsonMapper;
    }

    @KafkaListener(id = "catalog-query-stock-indexer", groupId = GROUP, topics = TOPIC)
    public void on(ConsumerRecord<String, String> record) {
        EventEnvelope event = EventEnvelope.from(record);
        if ("ProductAvailabilityChanged".equals(event.eventType())) {
            indexer.onAvailabilityChanged(event.payloadAs(jsonMapper, ProductAvailabilityChanged.class));
        }
        // Stock adjustments, low-stock alerts and reservations are not part of the search model.
    }
}
