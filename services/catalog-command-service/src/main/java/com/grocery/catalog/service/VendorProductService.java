package com.grocery.catalog.service;

import com.grocery.catalog.dto.ProductDtos.ProductRequest;
import com.grocery.catalog.dto.ProductDtos.ProductView;
import com.grocery.catalog.entity.Brand;
import com.grocery.catalog.entity.Product;
import com.grocery.catalog.entity.ProductStatus;
import com.grocery.catalog.mapper.ProductMapper;
import com.grocery.catalog.repository.BrandRepository;
import com.grocery.catalog.repository.CategoryRepository;
import com.grocery.catalog.repository.ProductRepository;
import com.grocery.catalog.repository.VendorRefRepository;
import com.grocery.common.id.Ids;
import com.grocery.common.security.CurrentUser;
import com.grocery.common.web.BusinessRuleException;
import com.grocery.common.web.ConflictException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiConsumer;

/**
 * The vendor's own product commands (contract §3). Ownership comes from the token's {@code vendor_id}
 * claim; another vendor's product is reported as not found. Every command follows the snapshot rule
 * documented on {@link SnapshotPublisher}: lock the product row first, then share-lock its category and
 * vendor projection, then change, bump the version and publish — in one transaction.
 */
@Service
public class VendorProductService {

    private final ProductRepository products;
    private final CategoryRepository categories;
    private final VendorRefRepository vendorRefs;
    private final BrandRepository brands;
    private final BrandService brandService;
    private final SnapshotPublisher snapshots;
    private final TransactionTemplate transaction;
    private final Clock clock;

    public VendorProductService(ProductRepository products, CategoryRepository categories,
                                VendorRefRepository vendorRefs, BrandRepository brands, BrandService brandService,
                                SnapshotPublisher snapshots, PlatformTransactionManager transactionManager,
                                Clock clock) {
        this.products = products;
        this.categories = categories;
        this.vendorRefs = vendorRefs;
        this.brands = brands;
        this.brandService = brandService;
        this.snapshots = snapshots;
        this.transaction = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    public ProductView create(CurrentUser user, ProductRequest request) {
        UUID vendorId = user.requireVendorId();
        Brand brand = brandService.findOrCreate(request.brandName());   // own transaction, before ours
        return transaction.execute(status -> {
            lockCategoryForProduct(request.categoryId());
            vendorRefs.lockForShare(vendorId);
            Product.Details details = ProductMapper.toDetails(request, brand);
            requireSkuFree(vendorId, details.sku(), null);

            Product product = Product.draft(Ids.newId(), vendorId, details, clock.instant());
            CategoryTree tree = categoryTree();
            snapshots.productsChanged(List.of(product), tree);
            saveAndFlush(product);
            return ProductMapper.toView(product, tree, brand);
        });
    }

    public ProductView update(CurrentUser user, UUID productId, ProductRequest request) {
        UUID vendorId = user.requireVendorId();
        Brand brand = brandService.findOrCreate(request.brandName());
        return transaction.execute(status -> {
            Product product = lockOwned(productId, vendorId);
            product.requireEditable();
            lockCategoryForProduct(request.categoryId());
            vendorRefs.lockForShare(vendorId);
            Product.Details details = ProductMapper.toDetails(request, brand);
            requireSkuFree(vendorId, details.sku(), productId);

            product.update(details, clock.instant());
            CategoryTree tree = categoryTree();
            snapshots.productsChanged(List.of(product), tree);
            saveAndFlush(product);
            return ProductMapper.toView(product, tree, brand);
        });
    }

    public ProductView publish(CurrentUser user, UUID productId) {
        return changeStatus(user, productId,
                (product, tree) -> product.publish(tree.effectiveActive(product.getCategoryId()), clock.instant()));
    }

    public ProductView unpublish(CurrentUser user, UUID productId) {
        return changeStatus(user, productId, (product, tree) -> product.unpublish(clock.instant()));
    }

    /** DELETE = archive: hidden everywhere, kept for order history. */
    public void archive(CurrentUser user, UUID productId) {
        changeStatus(user, productId, (product, tree) -> product.archive(clock.instant()));
    }

    private ProductView changeStatus(CurrentUser user, UUID productId, BiConsumer<Product, CategoryTree> change) {
        UUID vendorId = user.requireVendorId();
        return transaction.execute(status -> {
            Product product = lockOwned(productId, vendorId);
            if (product.getCategoryId() != null) {
                categories.lockForShare(product.getCategoryId());
            }
            vendorRefs.lockForShare(vendorId);
            CategoryTree tree = categoryTree();
            change.accept(product, tree);
            snapshots.productsChanged(List.of(product), tree);
            products.flush();
            Brand brand = product.getBrandId() == null ? null : brands.findById(product.getBrandId()).orElse(null);
            return ProductMapper.toView(product, tree, brand);
        });
    }

    /** {@code SELECT … FOR UPDATE} — the first statement of every command on an existing product. */
    private Product lockOwned(UUID productId, UUID vendorId) {
        return products.lockById(productId)
                .filter(p -> p.getVendorId().equals(vendorId))
                .orElseThrow(ProductQueryService::productNotFound);
    }

    /** {@code SELECT … FOR SHARE} on the product's (new) category; unknown → 422 with a field error. */
    private void lockCategoryForProduct(UUID categoryId) {
        if (categories.lockForShare(categoryId).isEmpty()) {
            throw new BusinessRuleException("CATEGORY_NOT_FOUND", "The category does not exist",
                    List.of(Map.of("field", "categoryId", "message", "category does not exist")));
        }
    }

    private void requireSkuFree(UUID vendorId, String sku, UUID excludeProductId) {
        if (sku != null && products.skuTaken(vendorId, sku, ProductStatus.ARCHIVED, excludeProductId)) {
            throw skuTaken(sku);
        }
    }

    /** The unique key (vendor_id, sku_key) is the last line of defence against a concurrent duplicate. */
    private void saveAndFlush(Product product) {
        try {
            products.saveAndFlush(product);
        } catch (DataIntegrityViolationException e) {
            if (Constraints.isViolation(e, "uk_product_vendor_sku")) {
                throw skuTaken(product.getSku());
            }
            throw e;
        }
    }

    private CategoryTree categoryTree() {
        return new CategoryTree(categories.findAll());
    }

    private static ConflictException skuTaken(String sku) {
        return new ConflictException("SKU_ALREADY_EXISTS", "You already have a product with SKU '" + sku + "'");
    }
}
