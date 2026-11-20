package com.grocery.inventory.event;

import com.grocery.common.kafka.EventEnvelope;
import com.grocery.common.kafka.InvalidEventException;
import com.grocery.inventory.service.RestockService;
import com.grocery.inventory.service.RestockService.RestockLine;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.UUID;

/**
 * vendor.events → stock (Phase 12 §4, consumer group {@code inventory-service.vendor-events}): {@code GoodsReceived}
 * adds the received units to stock ({@link RestockService#goodsReceived}). Every other event type on the topic (the
 * vendor lifecycle, the store order events) is ignored.
 */
@Component
public class VendorEventsListener {

    static final String TOPIC = "vendor.events";
    static final String GOODS_RECEIVED = "GoodsReceived";

    /** Our own view of the GoodsReceived payload: only the fields this service needs (tolerant reader). */
    record GoodsReceived(UUID vendorId, UUID purchaseOrderId, String purchaseOrderNumber, UUID receiptId,
                         List<Line> lines) {
    }

    record Line(UUID productId, Integer quantity) {
    }

    private final RestockService restock;
    private final JsonMapper jsonMapper;

    public VendorEventsListener(RestockService restock, JsonMapper jsonMapper) {
        this.restock = restock;
        this.jsonMapper = jsonMapper;
    }

    @KafkaListener(id = "inventory-vendor-events", groupId = RestockService.VENDOR_CONSUMER, topics = TOPIC)
    public void on(ConsumerRecord<String, String> record) {
        EventEnvelope event = EventEnvelope.from(record);
        if (!GOODS_RECEIVED.equals(event.eventType())) {
            return;
        }
        GoodsReceived receipt = event.payloadAs(jsonMapper, GoodsReceived.class);
        if (receipt.vendorId() == null || receipt.purchaseOrderId() == null || receipt.purchaseOrderNumber() == null) {
            throw new InvalidEventException("GoodsReceived " + event.eventId()
                    + " lacks vendorId, purchaseOrderId or purchaseOrderNumber");
        }
        if (receipt.lines() == null || receipt.lines().isEmpty()) {
            throw new InvalidEventException("GoodsReceived " + event.eventId() + " has no lines");
        }
        List<RestockLine> lines = receipt.lines().stream()
                .map(line -> {
                    if (line == null || line.productId() == null || line.quantity() == null || line.quantity() <= 0) {
                        throw new InvalidEventException("GoodsReceived " + event.eventId()
                                + " has a line without productId or a positive quantity");
                    }
                    return new RestockLine(line.productId(), line.quantity());
                })
                .toList();
        restock.goodsReceived(event.eventId(), receipt.vendorId(), receipt.purchaseOrderId(),
                receipt.purchaseOrderNumber(), lines);
    }
}
