package com.grocery.vendor.event;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Purchasing events PUBLISHED on {@code vendor.events} (v1, Phase 12 §4): aggregate {@code PurchaseOrder}, key
 * <b>vendorId</b> (so one vendor's receipts stay in order). The payload is exactly the central schema
 * {@code avro/vendor.events/GoodsReceived.avsc} (grocery-event-schemas 1.5.0; the outbox converts it strictly).
 * Consumers: inventory-service ({@code inventory-service.vendor-events}: stock INCREASE, reason RESTOCK), reporting.
 * The vendor lifecycle and store order consumers ignore it.
 */
public final class PurchasingEvents {

    public static final String TOPIC = VendorEvents.TOPIC;
    public static final String AGGREGATE = "PurchaseOrder";

    public static final String GOODS_RECEIVED = "GoodsReceived";

    private PurchasingEvents() {
    }

    /** One goods receipt of a purchase order: the units received now, at the purchase order's unit costs. */
    public record GoodsReceived(UUID vendorId, UUID purchaseOrderId, String purchaseOrderNumber, UUID receiptId,
                                UUID supplierId, List<GoodsReceivedLine> lines, Instant receivedAt) {
    }

    /** {@code unitCost}: 2 decimals. */
    public record GoodsReceivedLine(UUID productId, int quantity, BigDecimal unitCost) {
    }
}
