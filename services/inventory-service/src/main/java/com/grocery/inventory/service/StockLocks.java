package com.grocery.inventory.service;

import com.grocery.inventory.entity.StockItem;
import com.grocery.inventory.repository.StockItemRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Takes the stock row locks. <b>The single entry point for locking stock</b>.
 *
 * <h2>Locking and versioning rule (no lost updates, no overselling)</h2>
 * Every transaction that changes stock — vendor adjustment (single or bulk), threshold change, reserve, release,
 * expiry, commit, and the catalog projection — does this, in this order and in <em>one</em> transaction:
 * <ol>
 *   <li><b>Lock first.</b> {@code SELECT … FOR UPDATE} on the {@code stock_item} row(s) ({@link #lock},
 *       {@link #lockAll}) <b>before reading anything else</b>. Several products are always locked one by one in
 *       <b>ascending product id order</b> ({@link #BINARY_ORDER} = the byte order of the BINARY(16) primary key),
 *       so two transactions that touch overlapping products queue up instead of deadlocking. InnoDB creates a
 *       transaction's consistent-read snapshot at its first non-locking read, so everything read after the locks
 *       (product copy, reservation) includes every change committed by the previous lock holder.</li>
 *   <li><b>Check and apply</b> the change on the locked {@link StockItem} (rules live there), which increments
 *       {@code stock_version} on every quantity change (adjustment, set, reserve, release, commit).</li>
 *   <li><b>Write the ledger entry</b> ({@code stock_movement}) <b>and the events</b> (outbox, carrying the new
 *       {@code stockVersion}) in the same transaction; commit releases the locks.</li>
 * </ol>
 * Two simultaneous changes of the same product are therefore applied one after the other, each on top of the
 * other's result, and a higher {@code stockVersion} never describes older stock. The database's CHECK constraints
 * ({@code 0 ≤ reserved ≤ on_hand ≤ 1,000,000}) are the last line of defence. Lock waits that time out and deadlock
 * victims become 409 CONCURRENT_MODIFICATION (grocery-common's {@code DataAccessExceptionAdvice}) or a Kafka retry.
 *
 * <p><b>Reservation rows.</b> Release and commit lock the reservation ({@code FOR UPDATE} by primary key) after the
 * stock rows. Holding an order until pickup changes no stock and locks only the reservation. The expiry poller
 * is the one exception: it claims a batch of reservations with {@code FOR UPDATE SKIP LOCKED} first and then
 * locks all their stock rows at once (ascending) — why, and why that is safe, is documented on
 * {@link ReservationService#releaseExpired}.
 *
 * <p><b>Republish</b> does not lock and does NOT increment versions: it re-sends each product's current
 * availability with its current {@code stockVersion}; the read side ignores versions it already has.
 */
@Component
public class StockLocks {

    /** Unsigned byte order of the 16-byte id = the order of the BINARY(16) primary key in MySQL. */
    public static final Comparator<UUID> BINARY_ORDER = Comparator
            .comparing(UUID::getMostSignificantBits, Long::compareUnsigned)
            .thenComparing(UUID::getLeastSignificantBits, Long::compareUnsigned);

    private final StockItemRepository stockItems;

    public StockLocks(StockItemRepository stockItems) {
        this.stockItems = stockItems;
    }

    /** {@code SELECT … FOR UPDATE} on one product's stock row; null if the product is unknown. */
    public StockItem lock(UUID productId) {
        requireTransaction();
        return stockItems.lockById(productId).orElse(null);
    }

    /**
     * Locks the stock rows of several products in ascending product id order. Returns the locked rows by
     * product id (unknown products are missing), iterated in that same order.
     */
    public Map<UUID, StockItem> lockAll(Collection<UUID> productIds) {
        requireTransaction();
        List<UUID> ordered = productIds.stream().distinct().sorted(BINARY_ORDER).toList();
        Map<UUID, StockItem> locked = new LinkedHashMap<>();
        for (UUID productId : ordered) {
            stockItems.lockById(productId).ifPresent(stock -> locked.put(productId, stock));
        }
        return locked;
    }

    private static void requireTransaction() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Stock rows can only be locked inside the changing transaction");
        }
    }
}
