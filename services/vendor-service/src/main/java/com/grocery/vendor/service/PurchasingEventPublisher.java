package com.grocery.vendor.service;

import com.grocery.common.outbox.OutboxEvent;
import com.grocery.common.outbox.OutboxPublisher;
import com.grocery.vendor.entity.PurchaseOrder;
import com.grocery.vendor.entity.PurchaseReceipt;
import com.grocery.vendor.event.PurchasingEvents;
import com.grocery.vendor.event.PurchasingEvents.GoodsReceived;
import com.grocery.vendor.event.PurchasingEvents.GoodsReceivedLine;
import com.grocery.vendor.mapper.PurchasingMapper;
import org.springframework.stereotype.Component;

/**
 * Writes {@code GoodsReceived} (Phase 12 §4) to the outbox in the receiving transaction: topic {@code vendor.events},
 * aggregate {@code PurchaseOrder}, key = the vendor id. Built from the stored receipt (its lines in request order, the
 * purchase order's unit costs with 2 decimals).
 */
@Component
public class PurchasingEventPublisher {

    private final OutboxPublisher outbox;

    public PurchasingEventPublisher(OutboxPublisher outbox) {
        this.outbox = outbox;
    }

    public void goodsReceived(PurchaseOrder order, PurchaseReceipt receipt) {
        GoodsReceived payload = new GoodsReceived(order.getVendorId(), order.getId(), order.getNumber(),
                receipt.getId(), order.getSupplier().getId(),
                receipt.getLines().stream().map(line -> new GoodsReceivedLine(line.getProductId(), line.getQuantity(),
                        PurchasingMapper.money(line.getUnitCost()))).toList(),
                receipt.getReceivedAt());
        outbox.publish(OutboxEvent.of(PurchasingEvents.TOPIC, PurchasingEvents.AGGREGATE,
                order.getVendorId().toString(), PurchasingEvents.GOODS_RECEIVED, payload));
    }
}
