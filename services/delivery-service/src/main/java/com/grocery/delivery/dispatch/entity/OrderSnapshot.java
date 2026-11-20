package com.grocery.delivery.dispatch.entity;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * What a delivery keeps of the paid order (from {@code OrderConfirmed}): never changed afterwards. The store fields
 * come from {@code storeLocation} (null in events older than Phase 9), the drop fields from {@code deliveryAddress}.
 */
public record OrderSnapshot(UUID orderId, String orderNumber, String orderType, UUID customerId, UUID storeId,
                            String storeName, String storeCity, Double storeLatitude, Double storeLongitude,
                            String recipientName, String phone, String line1, String line2, String landmark,
                            String city, String postalCode, Double dropLatitude, Double dropLongitude,
                            String note, int itemCount, BigDecimal total, Instant confirmedAt) {
}
