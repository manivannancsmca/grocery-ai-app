package com.grocery.inventory.service;

import com.grocery.common.id.Ids;
import com.grocery.common.idempotency.ProcessedEventGuard;
import com.grocery.common.kafka.InvalidEventException;
import com.grocery.inventory.entity.AdjustmentReason;
import com.grocery.inventory.entity.MovementType;
import com.grocery.inventory.entity.ProductRef;
import com.grocery.inventory.entity.StockItem;
import com.grocery.inventory.entity.StockMovement;
import com.grocery.inventory.repository.ProductRefRepository;
import com.grocery.inventory.repository.StockItemRepository;
import jakarta.persistence.EntityManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Stock that comes back in from events (Phase 12, docs/api/phase-12-post-mvp-features.md §3, §4): an adjustment
 * {@code INCREASE} per product, as a system change (no actor), with the same ledger entry and stock events as a vendor
 * adjustment ({@code StockAdjusted}, and {@code ProductAvailabilityChanged} / {@code LowStockDetected} on transitions).
 * <ul>
 *   <li>{@code vendor.events → GoodsReceived} (consumer {@link #VENDOR_CONSUMER}): reason {@code RESTOCK}, note = the
 *       purchase order number ("PO-…"), reference = the purchase order id.</li>
 *   <li>{@code order.events → ReturnApproved} with {@code restock} true of a GROCERY order (consumer
 *       {@link OrderStockService#CONSUMER}; the listener drops the rest): reason {@code RETURN}, note "RT-… (order
 *       GA-…)", reference = the order id; {@code itemId} = productId.</li>
 * </ul>
 *
 * <p><b>Exactly once.</b> One transaction per event: the event is marked in {@code processed_event} (first statement),
 * then every product's stock row is locked in ascending id order ({@link StockLocks}), then the products are read,
 * changed, recorded and published — all commit or roll back together, so a redelivered event changes nothing. Lines
 * of the same product are added up (one movement per product).
 *
 * <p><b>Lines that cannot be applied.</b> A product another vendor owns, or an archived one (read-only), is skipped
 * with a warning — the rest of the event still applies. A product this service does not know: for a receipt the
 * event fails and is retried (the product may still be on its way from {@code catalog.events}; after the retries it is
 * dead-lettered and can be replayed), for a return it is skipped (the order was placed for a known product). Stock
 * above 1,000,000 makes the whole event {@link InvalidEventException} (dead-lettered at once, nothing applied).
 */
@Service
public class RestockService {

    public static final String VENDOR_CONSUMER = "inventory-service.vendor-events";
    private static final Logger log = LoggerFactory.getLogger(RestockService.class);
    private static final int MAX_NOTE = 300;

    /** A product and the units that came back. */
    public record RestockLine(UUID productId, int quantity) {
    }

    private final StockLocks locks;
    private final StockItemRepository stockItems;
    private final ProductRefRepository productRefs;
    private final StockEventPublisher events;
    private final ProcessedEventGuard processedEvents;
    private final EntityManager entityManager;
    private final TransactionTemplate transaction;
    private final Clock clock;

    public RestockService(StockLocks locks, StockItemRepository stockItems, ProductRefRepository productRefs,
                          StockEventPublisher events, ProcessedEventGuard processedEvents,
                          EntityManager entityManager, PlatformTransactionManager transactionManager, Clock clock) {
        this.locks = locks;
        this.stockItems = stockItems;
        this.productRefs = productRefs;
        this.events = events;
        this.processedEvents = processedEvents;
        this.entityManager = entityManager;
        this.transaction = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    /** {@code GoodsReceived}: the vendor's received units → INCREASE, reason RESTOCK. */
    public void goodsReceived(String eventId, UUID vendorId, UUID purchaseOrderId, String purchaseOrderNumber,
                              List<RestockLine> lines) {
        restock(eventId, VENDOR_CONSUMER, "GoodsReceived", vendorId, lines, AdjustmentReason.RESTOCK,
                truncate(purchaseOrderNumber), purchaseOrderId.toString(), true);
    }

    /** {@code ReturnApproved} (restock, GROCERY): the returned units → INCREASE, reason RETURN. */
    public void returnApproved(String eventId, UUID storeId, UUID orderId, String returnNumber, String orderNumber,
                               List<RestockLine> lines) {
        String note = returnNumber == null ? null
                : orderNumber == null ? returnNumber : returnNumber + " (order " + orderNumber + ")";
        restock(eventId, OrderStockService.CONSUMER, "ReturnApproved", storeId, lines, AdjustmentReason.RETURN,
                truncate(note), orderId.toString(), false);
    }

    private void restock(String eventId, String consumer, String eventType, UUID vendorId, List<RestockLine> lines,
                         AdjustmentReason reason, String note, String reference, boolean unknownIsRetried) {
        Map<UUID, Integer> quantities = lines.stream().collect(Collectors.toMap(RestockLine::productId,
                RestockLine::quantity, Integer::sum, LinkedHashMap::new));
        transaction.executeWithoutResult(status -> {
            if (!processedEvents.markProcessed(eventId, consumer)) {
                log.debug("{} {} already processed", eventType, eventId);
                return;
            }
            Map<UUID, StockItem> locked = locks.lockAll(quantities.keySet());            // 1. lock first
            Map<UUID, ProductRef> products = productRefs.findAllById(locked.keySet()).stream()
                    .collect(Collectors.toMap(ProductRef::getProductId, Function.identity()));
            Instant now = clock.instant();
            int applied = 0;
            for (Map.Entry<UUID, Integer> entry : quantities.entrySet()) {
                UUID productId = entry.getKey();
                StockItem stock = locked.get(productId);
                ProductRef product = products.get(productId);
                if (stock == null || product == null) {
                    if (unknownIsRetried) {
                        throw new IllegalStateException(eventType + " " + eventId + ": product " + productId
                                + " is not known to inventory yet");
                    }
                    log.warn("{} {}: unknown product {} skipped ({} unit(s))", eventType, eventId, productId,
                            entry.getValue());
                    continue;
                }
                if (vendorId != null && !stock.getVendorId().equals(vendorId)) {
                    log.error("{} {}: product {} belongs to vendor {}, not {}; skipped", eventType, eventId, productId,
                            stock.getVendorId(), vendorId);
                    continue;
                }
                if (product.isArchived()) {
                    log.warn("{} {}: product {} is archived; {} unit(s) not added", eventType, eventId, productId,
                            entry.getValue());
                    continue;
                }
                increase(eventType, eventId, stock, product, entry.getValue(), reason, note, reference, now);
                applied++;
            }
            stockItems.flush();
            log.info("{} {}: {} of {} product(s) restocked ({}, {})", eventType, eventId, applied, quantities.size(),
                    reason, note);
        });
    }

    /** Steps 2 and 3 of the locking rule on one locked row: change, ledger entry, events. */
    private void increase(String eventType, String eventId, StockItem stock, ProductRef product, int quantity,
                          AdjustmentReason reason, String note, String reference, Instant now) {
        int availableBefore = stock.available();
        int delta;
        try {
            delta = stock.adjust(MovementType.INCREASE, quantity, now);
        } catch (InventoryException e) {
            throw new InvalidEventException(eventType + " " + eventId + " cannot be applied to product "
                    + stock.getProductId() + ": " + e.errorCode() + " (" + e.getMessage() + ")", e);
        }
        StockMovement movement = StockMovement.record(Ids.newId(), stock, MovementType.INCREASE, delta, reason, note,
                reference, null, now);
        entityManager.persist(movement);
        events.adjusted(stock, movement);
        events.transitions(stock, availableBefore, product::getName);
    }

    private static String truncate(String value) {
        return value == null || value.length() <= MAX_NOTE ? value : value.substring(0, MAX_NOTE);
    }
}
