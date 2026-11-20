package com.grocery.vendor.service;

import com.grocery.common.idempotency.ProcessedEventGuard;
import com.grocery.vendor.entity.ProductRef;
import com.grocery.vendor.repository.ProductRefRepository;
import jakarta.persistence.EntityManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;

/**
 * Keeps {@code product_ref} in step with {@code catalog.events → ProductUpserted} (consumer
 * {@code vendor-service.catalog-events}), the same way pricing- and inventory-service keep theirs: idempotent per
 * event id, and a snapshot is applied only when its {@code productVersion} is newer than the stored one, so
 * redeliveries and out-of-order events never overwrite newer data. Every status is kept (ARCHIVED too: such a product
 * just cannot be put on a purchase order). The row is locked first ({@code SELECT … FOR UPDATE}).
 */
@Service
public class ProductProjectionService {

    public static final String CONSUMER = "vendor-service.catalog-events";
    private static final Logger log = LoggerFactory.getLogger(ProductProjectionService.class);

    private final ProductRefRepository products;
    private final ProcessedEventGuard processedEvents;
    private final EntityManager entityManager;
    private final Clock clock;

    public ProductProjectionService(ProductRefRepository products, ProcessedEventGuard processedEvents,
                                    EntityManager entityManager, Clock clock) {
        this.products = products;
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
        ProductRef product = products.lockById(snapshot.productId()).orElse(null);
        if (product == null) {
            entityManager.persist(ProductRef.from(snapshot, now));     // persist, not merge: no extra read
            log.debug("Product {} of vendor {} is now known ({})", snapshot.productId(), snapshot.vendorId(),
                    snapshot.status());
            return;
        }
        if (!product.getVendorId().equals(snapshot.vendorId())) {
            log.warn("ProductUpserted {} moves product {} from vendor {} to vendor {}", eventId, snapshot.productId(),
                    product.getVendorId(), snapshot.vendorId());
        }
        if (!product.applyIfNewer(snapshot, now)) {
            log.debug("Ignoring stale ProductUpserted {} for {} (version {} <= stored {})", eventId,
                    snapshot.productId(), snapshot.productVersion(), product.getProductVersion());
        }
    }
}
