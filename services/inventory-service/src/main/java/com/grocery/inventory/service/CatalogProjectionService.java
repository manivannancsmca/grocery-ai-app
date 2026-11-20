package com.grocery.inventory.service;

import com.grocery.common.idempotency.ProcessedEventGuard;
import com.grocery.inventory.entity.ProductRef;
import com.grocery.inventory.entity.StockItem;
import com.grocery.inventory.repository.ProductRefRepository;
import jakarta.persistence.EntityManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;

/**
 * Keeps {@code product_ref} in step with {@code catalog.events → ProductUpserted}. Idempotent per event id, and
 * a snapshot is applied only when its {@code productVersion} is newer than the stored one, so redeliveries and
 * out-of-order events never overwrite newer data. The first time a product is seen (any status) its stock row
 * is created with nothing on hand. Archived products are kept (read-only, hidden from lists).
 *
 * <p>Follows the locking rule of {@link StockLocks}: the product's stock row is locked first, so an archive
 * and a concurrent stock change are applied one after the other. Nothing here changes stock quantities, so
 * no stock event is published and {@code stock_version} stays.
 */
@Service
public class CatalogProjectionService {

    public static final String CONSUMER = "inventory-service.catalog-events";
    private static final Logger log = LoggerFactory.getLogger(CatalogProjectionService.class);

    private final StockLocks locks;
    private final ProductRefRepository productRefs;
    private final ProcessedEventGuard processedEvents;
    private final EntityManager entityManager;
    private final Clock clock;

    public CatalogProjectionService(StockLocks locks, ProductRefRepository productRefs,
                                    ProcessedEventGuard processedEvents, EntityManager entityManager, Clock clock) {
        this.locks = locks;
        this.productRefs = productRefs;
        this.processedEvents = processedEvents;
        this.entityManager = entityManager;
        this.clock = clock;
    }

    @Transactional
    public void productUpserted(String eventId, ProductRef.Snapshot snapshot) {
        if (!processedEvents.markProcessed(eventId, CONSUMER)) {
            log.debug("ProductUpserted {} already processed", eventId);
            return;
        }
        Instant now = clock.instant();
        StockItem stock = locks.lock(snapshot.productId());
        if (stock == null) {
            // persist (not merge): no extra read; product_ref first because of the foreign key
            entityManager.persist(ProductRef.from(snapshot, now));
            productRefs.flush();
            entityManager.persist(StockItem.empty(snapshot.productId(), snapshot.vendorId(), now));
            productRefs.flush();
            log.info("Product {} (vendor {}) is now tracked, status {}", snapshot.productId(), snapshot.vendorId(),
                    snapshot.status());
            return;
        }
        ProductRef ref = productRefs.findById(snapshot.productId())
                .orElseThrow(() -> new IllegalStateException("stock row without product_ref: " + snapshot.productId()));
        if (!ref.getVendorId().equals(snapshot.vendorId())) {
            log.warn("ProductUpserted {} names vendor {} for product {} of vendor {}; the vendor is kept",
                    eventId, snapshot.vendorId(), snapshot.productId(), ref.getVendorId());
        }
        if (!ref.applyIfNewer(snapshot, now)) {
            log.debug("Ignoring stale ProductUpserted {} for {} (version {} <= stored {})", eventId,
                    snapshot.productId(), snapshot.productVersion(), ref.getProductVersion());
        }
    }
}
