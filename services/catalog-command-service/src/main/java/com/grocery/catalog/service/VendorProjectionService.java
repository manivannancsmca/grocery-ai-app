package com.grocery.catalog.service;

import com.grocery.catalog.entity.Product;
import com.grocery.catalog.entity.ProductStatus;
import com.grocery.catalog.entity.VendorRef;
import com.grocery.catalog.repository.CategoryRepository;
import com.grocery.catalog.repository.ProductRepository;
import com.grocery.catalog.repository.VendorRefRepository;
import com.grocery.common.idempotency.ProcessedEventGuard;
import jakarta.persistence.EntityManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Keeps {@code vendor_ref} in step with {@code vendor.events} and re-snapshots the vendor's products when
 * what they show changes (vendor name, or active ↔ suspended). Idempotent per event id.
 */
@Service
public class VendorProjectionService {

    public static final String CONSUMER = "catalog-command.vendor-events";
    private static final Logger log = LoggerFactory.getLogger(VendorProjectionService.class);

    /** vendor.events event type → the vendor status it implies. Other event types are ignored. */
    private static final Map<String, String> STATUS_BY_EVENT = Map.of(
            "VendorRegistered", "DRAFT",
            "VendorSubmitted", "PENDING_APPROVAL",
            "VendorApproved", "APPROVED",
            "VendorRejected", "REJECTED",
            "VendorSuspended", VendorRef.SUSPENDED,
            "VendorReinstated", "APPROVED");

    private final VendorRefRepository vendorRefs;
    private final ProductRepository products;
    private final CategoryRepository categories;
    private final SnapshotPublisher snapshots;
    private final ProcessedEventGuard processedEvents;
    private final EntityManager entityManager;
    private final Clock clock;

    public VendorProjectionService(VendorRefRepository vendorRefs, ProductRepository products,
                                   CategoryRepository categories, SnapshotPublisher snapshots,
                                   ProcessedEventGuard processedEvents, EntityManager entityManager, Clock clock) {
        this.vendorRefs = vendorRefs;
        this.products = products;
        this.categories = categories;
        this.snapshots = snapshots;
        this.processedEvents = processedEvents;
        this.entityManager = entityManager;
        this.clock = clock;
    }

    /** The vendor status implied by an event type, or null if this service does not care about the event. */
    public static String statusFor(String eventType) {
        return STATUS_BY_EVENT.get(eventType);
    }

    @Transactional
    public void apply(String eventId, String eventType, UUID vendorId, UUID ownerUserId, String businessName) {
        String status = statusFor(eventType);
        if (status == null || !processedEvents.markProcessed(eventId, CONSUMER)) {
            return;
        }
        // Snapshot rule (see SnapshotPublisher): lock the vendor row, then the vendor's products, then read.
        VendorRef ref = vendorRefs.lockForUpdate(vendorId).orElse(null);
        boolean wasActive = VendorRef.isActive(ref);
        String oldName = VendorRef.nameOf(ref);
        if (ref == null) {
            ref = new VendorRef(vendorId);
            ref.apply(ownerUserId, businessName, status, clock.instant());
            entityManager.persist(ref);     // persist, not merge: no extra read before the product locks
        } else {
            ref.apply(ownerUserId, businessName, status, clock.instant());
        }
        entityManager.flush();

        boolean active = VendorRef.isActive(ref);
        if (wasActive != active || !Objects.equals(oldName, ref.getBusinessName())) {
            List<Product> live = products.lockLiveByVendorId(vendorId, ProductStatus.ARCHIVED);
            if (!live.isEmpty()) {
                snapshots.productsChanged(live, new CategoryTree(categories.findAll()));
            }
            log.info("{} for vendor {}: re-published {} product snapshot(s) (active={})",
                    eventType, vendorId, live.size(), active);
        }
    }
}
