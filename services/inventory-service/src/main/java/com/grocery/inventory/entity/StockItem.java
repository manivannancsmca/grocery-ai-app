package com.grocery.inventory.entity;

import com.grocery.inventory.service.InventoryException;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * The stock of one product, counted in sellable units.
 * <pre>
 * available = onHand − reserved      inStock = available &gt; 0      lowStock = threshold &gt; 0 and available ≤ threshold
 * </pre>
 * Every quantity change increments {@code stockVersion} (carried by the stock events). Callers must hold the
 * row lock ({@code SELECT … FOR UPDATE}, see {@code StockLocks}) before calling any mutator. The database CHECK
 * constraints ({@code 0 ≤ reserved ≤ on_hand ≤ 1,000,000}) back these rules up.
 */
@Entity
@Table(name = "stock_item")
public class StockItem {

    public static final int DEFAULT_LOW_STOCK_THRESHOLD = 5;

    @Id
    @Column(name = "product_id")
    private UUID productId;

    @Column(name = "vendor_id", nullable = false, updatable = false)
    private UUID vendorId;

    @Column(name = "on_hand", nullable = false)
    private int onHand;

    @Column(nullable = false)
    private int reserved;

    @Column(name = "low_stock_threshold", nullable = false)
    private int lowStockThreshold;

    @Column(name = "stock_version", nullable = false)
    private long stockVersion;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected StockItem() {
    }

    /** The stock row created when a product is first seen: nothing on hand, version 0. */
    public static StockItem empty(UUID productId, UUID vendorId, Instant now) {
        StockItem item = new StockItem();
        item.productId = productId;
        item.vendorId = vendorId;
        item.lowStockThreshold = DEFAULT_LOW_STOCK_THRESHOLD;
        item.createdAt = now;
        item.updatedAt = now;
        return item;
    }

    public int available() {
        return onHand - reserved;
    }

    public boolean inStock() {
        return available() > 0;
    }

    public boolean lowStock() {
        return lowStockThreshold > 0 && available() <= lowStockThreshold;
    }

    /**
     * A vendor adjustment. INCREASE adds, DECREASE removes at most the available stock (409 INSUFFICIENT_STOCK),
     * SET replaces on hand with a counted quantity of at least the reserved stock (409 STOCK_BELOW_RESERVED).
     * On hand never exceeds 1,000,000 (422 STOCK_LIMIT_EXCEEDED).
     *
     * @return the change of on hand (may be 0 for a SET to the current value)
     */
    public int adjust(MovementType type, int quantity, Instant now) {
        long newOnHand = switch (type) {
            case INCREASE -> (long) onHand + quantity;
            case DECREASE -> {
                if (quantity > available()) {
                    throw InventoryException.insufficientStock(available(), reserved);
                }
                yield onHand - quantity;
            }
            case SET -> {
                if (quantity < reserved) {
                    throw InventoryException.belowReserved(available(), reserved);
                }
                yield quantity;
            }
            default -> throw new IllegalArgumentException(type + " is not a vendor adjustment");
        };
        if (newOnHand > InventoryException.MAX_ON_HAND) {
            throw InventoryException.limitExceeded(newOnHand);
        }
        int delta = (int) (newOnHand - onHand);
        onHand = (int) newOnHand;
        changed(now);
        return delta;
    }

    /** Holds stock for an order. The caller has checked {@code quantity ≤ available()}. */
    public void reserve(int quantity, Instant now) {
        requirePositive(quantity);
        if (quantity > available()) {
            throw new IllegalStateException("Cannot reserve " + quantity + " of " + productId
                    + ": only " + available() + " available");
        }
        reserved += quantity;
        changed(now);
    }

    /** Gives a hold back: reserved − quantity. */
    public void release(int quantity, Instant now) {
        requirePositive(quantity);
        if (quantity > reserved) {
            throw new IllegalStateException("Cannot release " + quantity + " of " + productId
                    + ": only " + reserved + " reserved");
        }
        reserved -= quantity;
        changed(now);
    }

    /** A hold left the store: on hand − quantity and reserved − quantity (available is unchanged). */
    public void commit(int quantity, Instant now) {
        requirePositive(quantity);
        if (quantity > reserved) {
            throw new IllegalStateException("Cannot commit " + quantity + " of " + productId
                    + ": only " + reserved + " reserved");
        }
        reserved -= quantity;
        onHand -= quantity;
        changed(now);
    }

    /** Not a stock change: the version stays (no event depends on the threshold alone). */
    public void changeThreshold(int threshold, Instant now) {
        if (threshold < 0 || threshold > 100_000) {
            throw new IllegalArgumentException("threshold must be 0-100000");
        }
        this.lowStockThreshold = threshold;
        this.updatedAt = now;
    }

    private void changed(Instant now) {
        stockVersion++;
        updatedAt = now;
    }

    private static void requirePositive(int quantity) {
        if (quantity <= 0) {
            throw new IllegalArgumentException("quantity must be positive");
        }
    }

    public UUID getProductId() {
        return productId;
    }

    public UUID getVendorId() {
        return vendorId;
    }

    public int getOnHand() {
        return onHand;
    }

    public int getReserved() {
        return reserved;
    }

    public int getLowStockThreshold() {
        return lowStockThreshold;
    }

    public long getStockVersion() {
        return stockVersion;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
