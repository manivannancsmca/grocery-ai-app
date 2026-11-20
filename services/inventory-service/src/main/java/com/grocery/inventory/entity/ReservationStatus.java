package com.grocery.inventory.entity;

/**
 * <pre>
 * RESERVED ──release──▶ RELEASED      (stock given back)
 *    └─────commit───▶ COMMITTED       (stock left the store)
 * </pre>
 * Both end states are final.
 */
public enum ReservationStatus {
    RESERVED,
    RELEASED,
    COMMITTED
}
