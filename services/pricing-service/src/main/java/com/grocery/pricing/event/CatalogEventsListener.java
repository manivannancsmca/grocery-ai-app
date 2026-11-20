package com.grocery.pricing.event;

import com.grocery.common.kafka.EventEnvelope;
import com.grocery.common.kafka.InvalidEventException;
import com.grocery.pricing.entity.ProductRef;
import com.grocery.pricing.entity.ProductStatus;
import com.grocery.pricing.service.CatalogProjectionService;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.util.UUID;

/** catalog.events → the {@code product_ref} projection. Only {@code ProductUpserted} matters here. */
@Component
public class CatalogEventsListener {

    public static final String PRODUCT_UPSERTED = "ProductUpserted";

    /** Our own view of the ProductUpserted payload: only the fields this service needs (tolerant reader). */
    record ProductUpserted(UUID productId, UUID vendorId, String vendorName, String name, String unitLabel,
                           String sku, String status, Long productVersion) {
    }

    private final CatalogProjectionService projection;
    private final JsonMapper jsonMapper;

    public CatalogEventsListener(CatalogProjectionService projection, JsonMapper jsonMapper) {
        this.projection = projection;
        this.jsonMapper = jsonMapper;
    }

    @KafkaListener(id = "pricing-catalog-events", groupId = CatalogProjectionService.CONSUMER,
            topics = "catalog.events")
    public void on(ConsumerRecord<String, String> record) {
        EventEnvelope event = EventEnvelope.from(record);
        if (!PRODUCT_UPSERTED.equals(event.eventType())) {
            return;
        }
        ProductUpserted product = event.payloadAs(jsonMapper, ProductUpserted.class);
        if (product.productId() == null || product.vendorId() == null || product.name() == null
                || product.status() == null || product.productVersion() == null) {
            throw new InvalidEventException("ProductUpserted " + event.eventId()
                    + " lacks productId, vendorId, name, status or productVersion");
        }
        ProductStatus status;
        try {
            status = ProductStatus.valueOf(product.status());
        } catch (IllegalArgumentException e) {
            throw new InvalidEventException("ProductUpserted " + event.eventId() + " has an unknown status '"
                    + product.status() + "'", e);
        }
        projection.apply(event.eventId(), product.productId(), new ProductRef.Snapshot(product.vendorId(),
                product.vendorName(), product.name(), product.unitLabel(), product.sku(), status,
                product.productVersion()));
    }
}
