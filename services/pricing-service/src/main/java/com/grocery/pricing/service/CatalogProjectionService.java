package com.grocery.pricing.service;

import com.grocery.common.idempotency.ProcessedEventGuard;
import com.grocery.pricing.cache.CachedPrice;
import com.grocery.pricing.cache.PriceQuoteCache;
import com.grocery.pricing.entity.PriceChangeStatus;
import com.grocery.pricing.entity.ProductRef;
import com.grocery.pricing.event.PricingEvents;
import com.grocery.pricing.event.PricingEvents.PriceChangeCancelled;
import com.grocery.pricing.repository.PriceChangeRequestRepository;
import com.grocery.pricing.repository.ProductPriceRepository;
import jakarta.persistence.EntityManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

/**
 * Keeps {@code product_ref} in step with {@code catalog.events} → {@code ProductUpserted}: idempotent per event id,
 * and a snapshot is applied only when its {@code productVersion} is newer than the stored one (duplicates,
 * replays and out-of-order events never win). When a product becomes ARCHIVED its pending price-change request
 * is cancelled automatically and its quote-cache entry becomes a tombstone. Same locking rule as every price
 * change ({@link PriceWriter}): the {@code product_ref} row is locked first.
 */
@Service
public class CatalogProjectionService {

    public static final String CONSUMER = "pricing-service.catalog-events";
    static final String PRODUCT_ARCHIVED = "Product archived";
    private static final Logger log = LoggerFactory.getLogger(CatalogProjectionService.class);

    private final PriceWriter writer;
    private final ProductPriceRepository prices;
    private final PriceChangeRequestRepository requests;
    private final PriceQuoteCache cache;
    private final ProcessedEventGuard processedEvents;
    private final EntityManager entityManager;
    private final Clock clock;

    public CatalogProjectionService(PriceWriter writer, ProductPriceRepository prices,
                                    PriceChangeRequestRepository requests, PriceQuoteCache cache,
                                    ProcessedEventGuard processedEvents, EntityManager entityManager, Clock clock) {
        this.writer = writer;
        this.prices = prices;
        this.requests = requests;
        this.cache = cache;
        this.processedEvents = processedEvents;
        this.entityManager = entityManager;
        this.clock = clock;
    }

    @Transactional
    public void apply(String eventId, UUID productId, ProductRef.Snapshot snapshot) {
        if (!processedEvents.markProcessed(eventId, CONSUMER)) {
            log.debug("ProductUpserted {} for {} already processed", eventId, productId);
            return;
        }
        Instant now = clock.instant();
        ProductRef product = writer.lockProduct(productId).orElse(null);
        boolean wasArchived = product != null && product.isArchived();
        if (product == null) {
            product = ProductRef.create(productId, snapshot, now);
            entityManager.persist(product);          // persist, not merge: no extra read after the lock
        } else if (product.isOlderThan(snapshot)) {
            product.apply(snapshot, now);
        } else {
            log.debug("Ignoring ProductUpserted {} for {}: version {} is not newer than {}", eventId, productId,
                    snapshot.productVersion(), product.getProductVersion());
            return;
        }
        entityManager.flush();

        if (product.isArchived() && !wasArchived) {
            onArchived(product, now);
        }
    }

    private void onArchived(ProductRef product, Instant now) {
        UUID productId = product.getProductId();
        requests.lockPending(productId, PriceChangeStatus.PENDING).ifPresent(request -> {
            request.cancel(null, PRODUCT_ARCHIVED, now);
            entityManager.flush();
            writer.publish(productId, PricingEvents.PRICE_CHANGE_CANCELLED, new PriceChangeCancelled(
                    request.getId(), productId, request.getVendorId(), null, PRODUCT_ARCHIVED));
            log.info("Product {} archived: cancelled pending price-change request {}", productId, request.getId());
        });
        prices.findById(productId).ifPresent(price -> cache.putAfterCommit(CachedPrice.of(price, product)));
    }
}
