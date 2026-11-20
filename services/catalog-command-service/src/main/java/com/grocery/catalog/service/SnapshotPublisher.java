package com.grocery.catalog.service;

import com.grocery.catalog.entity.Brand;
import com.grocery.catalog.entity.Category;
import com.grocery.catalog.entity.Product;
import com.grocery.catalog.entity.VendorRef;
import com.grocery.catalog.event.CatalogEvents;
import com.grocery.catalog.mapper.SnapshotMapper;
import com.grocery.catalog.repository.BrandRepository;
import com.grocery.catalog.repository.VendorRefRepository;
import com.grocery.common.outbox.OutboxEvent;
import com.grocery.common.outbox.OutboxPublisher;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Writes {@code ProductUpserted} / {@code CategoryChanged} snapshots to the outbox, in the caller's transaction.
 *
 * <h2>Snapshot versioning and locking rule</h2>
 * The read side stores {@code productVersion} / {@code categoryVersion} as Elasticsearch's <em>external
 * version</em> and drops every event whose version is not newer. That is only safe if <b>a higher version
 * never carries older data</b>. Every transaction that changes anything appearing in a snapshot therefore:
 * <ol>
 *   <li><b>first</b> locks the affected aggregate rows with a pessimistic write lock
 *       ({@code SELECT … FOR UPDATE}: {@code ProductRepository.lockById / lockLiveBy…},
 *       {@code CategoryRepository.lockAll}, {@code VendorRefRepository.lockForUpdate}) — <b>before reading
 *       anything else</b>. InnoDB creates a transaction's consistent-read snapshot at its first
 *       non-locking read, so everything read after the locks includes every change committed by the
 *       previous lock holder;</li>
 *   <li>then increments the version on the locked row ({@code Product.nextVersion()} /
 *       {@code Category.nextVersion()});</li>
 *   <li>builds the snapshot from the current state and writes the outbox event — all in one transaction.</li>
 * </ol>
 * Two writers of the same aggregate are thus serialised, and the one that commits later both reads the
 * newer data and produces the higher version. Snapshot data owned by other aggregates is protected the
 * same way: category changes lock the whole category tree and then the subtree's products; vendor events
 * lock the {@code vendor_ref} row and then the vendor's products; and product commands, after locking the
 * product, take a shared lock ({@code FOR SHARE}) on the product's category and {@code vendor_ref} row,
 * so a product that moves into a category (or is created) while that category is being renamed is either
 * re-snapshotted by the rename or reads the new name itself. Opposite lock orders can deadlock in rare
 * races; InnoDB then aborts one transaction (HTTP 409 CONCURRENT_MODIFICATION, Kafka retry).
 *
 * <p><b>Republish</b> does NOT increment versions: it re-sends the current state with the current version.
 * The read side ignores equal versions for documents it has and recreates documents it lost.
 */
@Component
public class SnapshotPublisher {

    private final OutboxPublisher outbox;
    private final BrandRepository brands;
    private final VendorRefRepository vendorRefs;

    public SnapshotPublisher(OutboxPublisher outbox, BrandRepository brands, VendorRefRepository vendorRefs) {
        this.outbox = outbox;
        this.brands = brands;
        this.vendorRefs = vendorRefs;
    }

    /** Increments each product's version and publishes its snapshot. The caller holds the row locks. */
    public void productsChanged(List<Product> products, CategoryTree tree) {
        products.forEach(Product::nextVersion);
        publishProducts(products, tree);
    }

    /**
     * Re-sends the current snapshots without changing versions. Archived products whose category was
     * deleted afterwards have no category to send and are skipped (the read side already has them as
     * ARCHIVED). Returns the number of snapshots written.
     */
    public int republishProducts(List<Product> products, CategoryTree tree) {
        List<Product> sendable = products.stream().filter(p -> tree.contains(p.getCategoryId())).toList();
        publishProducts(sendable, tree);
        return sendable.size();
    }

    /** Increments the category's version and publishes it. The caller holds the tree lock. */
    public void categoryChanged(Category category, CategoryTree tree) {
        category.nextVersion();
        publishCategory(category, tree, false);
    }

    /** Last snapshot of a category that is about to be deleted ({@code deleted=true}, higher version). */
    public void categoryDeleted(Category category, CategoryTree tree) {
        category.nextVersion();
        publishCategory(category, tree, true);
    }

    public void republishCategory(Category category, CategoryTree tree) {
        publishCategory(category, tree, false);
    }

    private void publishProducts(List<Product> products, CategoryTree tree) {
        if (products.isEmpty()) {
            return;
        }
        Map<UUID, Brand> brandsById = byId(brands.findAllById(ids(products, Product::getBrandId)), Brand::getId);
        Map<UUID, VendorRef> refsById = byId(vendorRefs.findAllById(ids(products, Product::getVendorId)),
                VendorRef::getVendorId);
        for (Product product : products) {
            CatalogEvents.ProductUpserted snapshot = SnapshotMapper.toSnapshot(product, tree,
                    brandsById.get(product.getBrandId()), refsById.get(product.getVendorId()));
            outbox.publish(OutboxEvent.of(CatalogEvents.TOPIC, CatalogEvents.PRODUCT_AGGREGATE,
                    product.getId().toString(), CatalogEvents.PRODUCT_UPSERTED, snapshot));
        }
    }

    private void publishCategory(Category category, CategoryTree tree, boolean deleted) {
        outbox.publish(OutboxEvent.of(CatalogEvents.TOPIC, CatalogEvents.CATEGORY_AGGREGATE,
                category.getId().toString(), CatalogEvents.CATEGORY_CHANGED,
                SnapshotMapper.toSnapshot(category, tree, deleted)));
    }

    private static List<UUID> ids(Collection<Product> products, Function<Product, UUID> id) {
        return products.stream().map(id).filter(Objects::nonNull).distinct().toList();
    }

    private static <T> Map<UUID, T> byId(List<T> items, Function<T, UUID> id) {
        return items.stream().collect(Collectors.toMap(id, Function.identity()));
    }
}
