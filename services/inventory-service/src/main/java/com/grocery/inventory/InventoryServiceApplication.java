package com.grocery.inventory;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Inventory service (Phase 4, order saga from Phase 6): stock per grocery product (on hand, reserved), the stock
 * movement ledger, reservations for orders and low-stock thresholds. Products arrive from {@code catalog.events};
 * grocery orders from {@code order.events} are reserved, held until pickup, released and expired. Stock and
 * reservation changes are published on {@code inventory.events} through the outbox. Every stock change follows the
 * locking rule documented on {@link com.grocery.inventory.service.StockLocks}.
 */
@SpringBootApplication
public class InventoryServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(InventoryServiceApplication.class, args);
    }
}
