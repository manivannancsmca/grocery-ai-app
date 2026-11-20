package com.grocery.vendor.event;

import com.grocery.common.kafka.EventEnvelope;
import com.grocery.common.kafka.InvalidEventException;
import com.grocery.vendor.entity.ProductRef;
import com.grocery.vendor.entity.ProductStatus;
import com.grocery.vendor.service.ProductProjectionService;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.util.UUID;

/**
 * catalog.events → the {@code product_ref} projection (Phase 12 §4: purchase orders may name only the vendor's own
 * products). Only {@code ProductUpserted} is used; every other event type on the topic is ignored.
 */
@Component
public class CatalogEventsListener {

    static final String PRODUCT_UPSERTED = "ProductUpserted";

    /** Our own view of the ProductUpserted payload: only the fields this service needs (tolerant reader). */
    record ProductUpserted(UUID productId, UUID vendorId, String name, String unitLabel, String sku, String status,
                           Long productVersion) {
    }

    private final ProductProjectionService projection;
    private final JsonMapper jsonMapper;

    public CatalogEventsListener(ProductProjectionService projection, JsonMapper jsonMapper) {
        this.projection = projection;
        this.jsonMapper = jsonMapper;
    }

    @KafkaListener(id = "vendor-catalog-events", groupId = ProductProjectionService.CONSUMER, topics = "catalog.events")
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
            throw new InvalidEventException("ProductUpserted " + event.eventId() + " has unknown status "
                    + product.status(), e);
        }
        projection.productUpserted(event.eventId(), new ProductRef.Snapshot(product.productId(), product.vendorId(),
                product.name(), product.unitLabel(), product.sku(), status, product.productVersion()));
    }
}
