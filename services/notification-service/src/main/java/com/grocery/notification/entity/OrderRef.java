package com.grocery.notification.entity;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * A row of the {@code order_ref} projection: what payment events (which carry only the orderId) need to know about
 * their order. {@code orderType}, {@code storeId}, {@code storeName} and {@code total} may be null when the order's
 * {@code OrderCreated} was never seen. {@code deliveryId} / {@code partnerId} (Phase 9): the delivery partner assigned
 * to the order (from {@code DeliveryPartnerAssigned}), null while there is none.
 */
public record OrderRef(UUID orderId, String orderNumber, String orderType, UUID customerId, UUID storeId,
                       String storeName, BigDecimal total, String status, UUID deliveryId, UUID partnerId) {

    /** An order without a delivery partner (what order events write; the partner columns are left alone). */
    public OrderRef(UUID orderId, String orderNumber, String orderType, UUID customerId, UUID storeId,
                    String storeName, BigDecimal total, String status) {
        this(orderId, orderNumber, orderType, customerId, storeId, storeName, total, status, null, null);
    }
}
