package com.grocery.order.event;

import com.grocery.order.event.StoreOrderEvents.Store;
import com.grocery.order.service.OrderEventService;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * vendor.events → orders (consumer group {@code order-service.vendor-events}, Phase 8): the vendor's decisions on its
 * GROCERY orders — {@code VendorOrderAccepted}, {@code VendorOrderPreparing} (packing),
 * {@code VendorOrderReadyForPickup}, {@code VendorOrderRejected}. {@code VendorOrderReceived} and the vendor lifecycle
 * events are skipped without touching the database. See {@link StoreOrderEvents}.
 */
@Component
public class VendorEventsListener {

    static final String TOPIC = "vendor.events";

    private final StoreOrderEvents storeEvents;

    public VendorEventsListener(StoreOrderEvents storeEvents) {
        this.storeEvents = storeEvents;
    }

    @KafkaListener(id = "order-vendor-events", groupId = OrderEventService.VENDOR_CONSUMER, topics = TOPIC)
    public void on(ConsumerRecord<String, String> record) {
        storeEvents.on(record, Store.VENDOR);
    }
}
