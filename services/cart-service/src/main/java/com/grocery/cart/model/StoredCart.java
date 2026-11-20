package com.grocery.cart.model;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * A customer's cart as stored in the {@code data} field of {@code cart:v1:<customerId>} (JSON). Immutable: every
 * change returns a new instance. Invariant: {@code store} is null exactly when there are no lines.
 *
 * <p>Phase 12: one {@code coupon} per cart (null = none; carts stored before have none). It stays when the last line
 * is removed, goes with {@link #cleared} (DELETE /api/cart, the cart an order was placed from), and a STORE coupon
 * goes when the cart moves to another store's items ({@link #withLine}).
 *
 * @param updatedAt the last change; null for a cart that was never written
 */
public record StoredCart(Store store, List<StoredLine> lines, Instant updatedAt, StoredCoupon coupon) {

    public static final StoredCart EMPTY = new StoredCart(null, List.of(), null, null);

    public StoredCart {
        lines = lines == null ? List.of() : List.copyOf(lines);
        if (lines.isEmpty()) {
            store = null;
        }
    }

    @JsonIgnore
    public boolean isEmpty() {
        return lines.isEmpty();
    }

    @JsonIgnore
    public List<UUID> itemIds() {
        return lines.stream().map(StoredLine::itemId).toList();
    }

    public StoredLine line(UUID itemId) {
        return lines.stream().filter(line -> line.itemId().equals(itemId)).findFirst().orElse(null);
    }

    /** True when this is a cart of {@code type} for the store {@code storeId}. */
    public boolean belongsTo(StoreType type, UUID storeId) {
        return store != null && store.type() == type && store.id().equals(storeId);
    }

    /**
     * Replaces the line with the same item id (keeping its position) or appends it; refreshes the store. A STORE
     * coupon of another store than {@code newStore} is removed (the customer changed to another store's items).
     */
    public StoredCart withLine(Store newStore, StoredLine line, Instant now) {
        List<StoredLine> updated = new ArrayList<>(lines);
        int index = indexOf(line.itemId());
        if (index >= 0) {
            updated.set(index, line);
        } else {
            updated.add(line);
        }
        StoredCoupon kept = coupon != null && coupon.isStoreCouponOtherThan(newStore) ? null : coupon;
        return new StoredCart(newStore, updated, now, kept);
    }

    public StoredCart without(UUID itemId, Instant now) {
        if (indexOf(itemId) < 0) {
            return this;
        }
        List<StoredLine> updated = new ArrayList<>(lines);
        updated.removeIf(line -> line.itemId().equals(itemId));
        return new StoredCart(store, updated, now, coupon);
    }

    /** Empty, without a coupon; the same instance when there is nothing to clear. */
    public StoredCart cleared(Instant now) {
        return isEmpty() && coupon == null ? this : new StoredCart(null, List.of(), now, null);
    }

    /** The lines removed (replacing the cart with another store's item), the coupon kept for {@link #withLine}. */
    @JsonIgnore
    public StoredCart withoutLines() {
        return new StoredCart(null, List.of(), updatedAt, coupon);
    }

    /** Applies (or replaces) the cart's one coupon. */
    public StoredCart withCoupon(StoredCoupon newCoupon, Instant now) {
        return new StoredCart(store, lines, now, newCoupon);
    }

    /** Removes the coupon; the same instance when there is none. */
    public StoredCart withoutCoupon(Instant now) {
        return coupon == null ? this : new StoredCart(store, lines, now, null);
    }

    private int indexOf(UUID itemId) {
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).itemId().equals(itemId)) {
                return i;
            }
        }
        return -1;
    }
}
