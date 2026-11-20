package com.grocery.restaurant.repository;

import com.grocery.restaurant.entity.StoreOrderStatus;

import java.math.BigDecimal;
import java.time.Instant;

/** What the sales report needs of one store order (no lines, no history). */
public record ReportRow(Instant receivedAt, StoreOrderStatus status, Instant acceptedAt, Boolean rejectionAuto,
                        BigDecimal subtotal, int itemCount) {
}
