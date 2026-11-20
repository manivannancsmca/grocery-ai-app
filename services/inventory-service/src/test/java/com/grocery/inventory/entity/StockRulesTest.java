package com.grocery.inventory.entity;

import com.grocery.inventory.service.InventoryException;
import com.grocery.inventory.service.StockLocks;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The stock arithmetic and its errors, without a database. */
class StockRulesTest {

    private static final Instant NOW = Instant.parse("2026-11-11T10:15:30Z");

    private static StockItem stock(int onHand, int reserved) {
        StockItem item = StockItem.empty(UUID.randomUUID(), UUID.randomUUID(), NOW);
        if (onHand > 0) {
            item.adjust(MovementType.SET, onHand, NOW);
        }
        if (reserved > 0) {
            item.reserve(reserved, NOW);
        }
        return item;
    }

    @Test
    void derivedFlags() {
        StockItem empty = StockItem.empty(UUID.randomUUID(), UUID.randomUUID(), NOW);
        assertThat(empty.available()).isZero();
        assertThat(empty.inStock()).isFalse();
        assertThat(empty.lowStock()).isTrue();                    // threshold 5 > 0 and 0 ≤ 5
        assertThat(empty.getStockVersion()).isZero();

        StockItem item = stock(12, 2);
        assertThat(item.available()).isEqualTo(10);
        assertThat(item.inStock()).isTrue();
        assertThat(item.lowStock()).isFalse();
        item.changeThreshold(10, NOW);
        assertThat(item.lowStock()).isTrue();                     // available == threshold counts as low
        item.changeThreshold(0, NOW);
        assertThat(item.lowStock()).isFalse();                    // 0 = no alerts
    }

    @Test
    void adjustmentsChangeOnHandAndAlwaysTheVersion() {
        StockItem item = stock(0, 0);
        assertThat(item.adjust(MovementType.INCREASE, 10, NOW)).isEqualTo(10);
        assertThat(item.adjust(MovementType.DECREASE, 4, NOW)).isEqualTo(-4);
        assertThat(item.adjust(MovementType.SET, 6, NOW)).isZero();
        assertThat(item.adjust(MovementType.SET, 9, NOW)).isEqualTo(3);
        assertThat(item.getOnHand()).isEqualTo(9);
        assertThat(item.getStockVersion()).isEqualTo(4);
        assertThatThrownBy(() -> item.adjust(MovementType.RESERVE, 1, NOW)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void stockNeverGoesBelowReservedOrAboveTheLimit() {
        StockItem item = stock(10, 4);
        long version = item.getStockVersion();
        assertThatThrownBy(() -> item.adjust(MovementType.DECREASE, 7, NOW))
                .isInstanceOfSatisfying(InventoryException.class, e -> {
                    assertThat(e.errorCode()).isEqualTo("INSUFFICIENT_STOCK");
                    assertThat(e.properties()).containsEntry("available", 6).containsEntry("reserved", 4);
                });
        assertThatThrownBy(() -> item.adjust(MovementType.SET, 3, NOW))
                .isInstanceOfSatisfying(InventoryException.class, e -> {
                    assertThat(e.errorCode()).isEqualTo("STOCK_BELOW_RESERVED");
                    assertThat(e.properties()).containsEntry("available", 6).containsEntry("reserved", 4);
                });
        item.adjust(MovementType.SET, 1_000_000, NOW);
        assertThatThrownBy(() -> item.adjust(MovementType.INCREASE, 1, NOW))
                .isInstanceOfSatisfying(InventoryException.class,
                        e -> assertThat(e.errorCode()).isEqualTo("STOCK_LIMIT_EXCEEDED"));
        assertThat(item.getOnHand()).isEqualTo(1_000_000);
        assertThat(item.getStockVersion()).isEqualTo(version + 1);
        assertThat(InventoryException.productNotFound().forProduct(item.getProductId()).properties())
                .containsEntry("productId", item.getProductId().toString());
    }

    @Test
    void reserveReleaseCommit() {
        StockItem item = stock(10, 0);
        item.reserve(4, NOW);
        assertThat(item.available()).isEqualTo(6);
        item.release(1, NOW);
        assertThat(item.getReserved()).isEqualTo(3);
        item.commit(3, NOW);
        assertThat(item.getOnHand()).isEqualTo(7);
        assertThat(item.getReserved()).isZero();
        assertThat(item.available()).isEqualTo(7);
        assertThat(item.getStockVersion()).isEqualTo(4);          // set, reserve, release, commit
        assertThatThrownBy(() -> item.reserve(8, NOW)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> item.release(1, NOW)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> item.commit(1, NOW)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void locksAreTakenInTheDatabasesByteOrder() {
        UUID low = UUID.fromString("01900000-0000-7000-8000-000000000000");
        UUID mid = UUID.fromString("7fffffff-ffff-7fff-bfff-ffffffffffff");
        UUID high = UUID.fromString("f0000000-0000-4000-8000-000000000000");   // negative msb
        assertThat(high.compareTo(low)).isNegative();                           // Java's signed order disagrees
        assertThat(List.of(high, mid, low).stream().sorted(StockLocks.BINARY_ORDER).toList())
                .containsExactly(low, mid, high);
    }
}
