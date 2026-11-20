package com.grocery.catalogquery.event;

import com.grocery.catalogquery.event.EventPayloads.CategoryChanged;
import com.grocery.catalogquery.event.EventPayloads.ProductUpserted;
import com.grocery.catalogquery.service.CatalogIndexer;
import com.grocery.common.kafka.EventEnvelope;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * catalog.events → products and categories indexes (group {@code catalog-query.product-indexer}).
 * Idempotency comes from external versioning, so there is no processed_event table: replaying is harmless.
 */
@Component
public class CatalogEventsListener {

    public static final String GROUP = "catalog-query.product-indexer";
    public static final String TOPIC = "catalog.events";

    private final CatalogIndexer indexer;
    private final JsonMapper jsonMapper;

    public CatalogEventsListener(CatalogIndexer indexer, JsonMapper jsonMapper) {
        this.indexer = indexer;
        this.jsonMapper = jsonMapper;
    }

    @KafkaListener(id = "catalog-query-product-indexer", groupId = GROUP, topics = TOPIC)
    public void on(ConsumerRecord<String, String> record) {
        EventEnvelope event = EventEnvelope.from(record);
        switch (event.eventType()) {
            case "ProductUpserted" -> indexer.onProductUpserted(event.payloadAs(jsonMapper, ProductUpserted.class));
            case "CategoryChanged" -> indexer.onCategoryChanged(event.payloadAs(jsonMapper, CategoryChanged.class));
            default -> {
                // Other catalog events are not part of the search model.
            }
        }
    }
}
