package com.grocery.catalog.event;

import com.grocery.catalog.service.VendorProjectionService;
import com.grocery.common.kafka.EventEnvelope;
import com.grocery.common.kafka.InvalidEventException;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.util.UUID;

/**
 * vendor.events → the {@code vendor_ref} projection (vendor name and suspension), which hides or shows
 * the vendor's products in search via new product snapshots.
 */
@Component
public class VendorEventsListener {

    /** Our own view of the vendor lifecycle payload: only the fields this service needs. */
    record VendorLifecycle(UUID vendorId, UUID ownerUserId, String businessName) {
    }

    private final VendorProjectionService projection;
    private final JsonMapper jsonMapper;

    public VendorEventsListener(VendorProjectionService projection, JsonMapper jsonMapper) {
        this.projection = projection;
        this.jsonMapper = jsonMapper;
    }

    @KafkaListener(id = "catalog-vendor-events", groupId = VendorProjectionService.CONSUMER, topics = "vendor.events")
    public void on(ConsumerRecord<String, String> record) {
        EventEnvelope event = EventEnvelope.from(record);
        if (VendorProjectionService.statusFor(event.eventType()) == null) {
            return;
        }
        VendorLifecycle vendor = event.payloadAs(jsonMapper, VendorLifecycle.class);
        if (vendor.vendorId() == null) {
            throw new InvalidEventException(event.eventType() + " " + event.eventId() + " has no vendorId");
        }
        projection.apply(event.eventId(), event.eventType(), vendor.vendorId(), vendor.ownerUserId(),
                vendor.businessName());
    }
}
