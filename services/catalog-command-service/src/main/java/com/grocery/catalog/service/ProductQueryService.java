package com.grocery.catalog.service;

import com.grocery.catalog.dto.ProductDtos.AdminProductView;
import com.grocery.catalog.dto.ProductDtos.ProductView;
import com.grocery.catalog.dto.ProductExports;
import com.grocery.catalog.entity.Brand;
import com.grocery.catalog.entity.Category;
import com.grocery.catalog.entity.Product;
import com.grocery.catalog.entity.ProductStatus;
import com.grocery.catalog.entity.VendorRef;
import com.grocery.catalog.mapper.ProductMapper;
import com.grocery.catalog.repository.BrandRepository;
import com.grocery.catalog.repository.CategoryRepository;
import com.grocery.catalog.repository.ProductRepository;
import com.grocery.catalog.repository.VendorRefRepository;
import com.grocery.common.data.Pages;
import com.grocery.common.export.ExportQueries;
import com.grocery.common.export.ExportSort.Order;
import com.grocery.common.export.RowSource;
import com.grocery.common.security.CurrentUser;
import com.grocery.common.web.BadRequestException;
import com.grocery.common.web.NotFoundException;
import com.grocery.common.web.PageResponse;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Product lists and details for vendors and admins, straight from MySQL (the public catalog is served by
 * catalog-query from Elasticsearch). Lists are sorted by most recently changed; ARCHIVED products are
 * listed only when {@code status=ARCHIVED} is asked for. The exports read the same lists in their own short
 * transactions (see {@link ExportQueries}), so they opt out of the class-level one.
 */
@Service
@Transactional(readOnly = true)
public class ProductQueryService {

    private static final Sort NEWEST_FIRST = Sort.by(Sort.Direction.DESC, "updatedAt").and(Sort.by(Sort.Direction.DESC, "id"));
    private static final char LIKE_ESCAPE = '!';

    private final ProductRepository products;
    private final CategoryRepository categories;
    private final BrandRepository brands;
    private final VendorRefRepository vendorRefs;
    private final ExportQueries exports;

    public ProductQueryService(ProductRepository products, CategoryRepository categories, BrandRepository brands,
                               VendorRefRepository vendorRefs, ExportQueries exports) {
        this.products = products;
        this.categories = categories;
        this.brands = brands;
        this.vendorRefs = vendorRefs;
        this.exports = exports;
    }

    public PageResponse<ProductView> vendorList(CurrentUser user, String status, String q, Integer page, Integer size) {
        Page<Product> found = products.findAll(vendorFilter(user, status, q), Pages.request(page, size, NEWEST_FIRST));
        Lookups lookups = lookups(found.getContent(), false);
        return Pages.toResponse(found, p -> ProductMapper.toView(p, lookups.tree, lookups.brand(p)));
    }

    /**
     * {@code GET /api/vendor/products/export}: the vendor's list with its filters, every page, most recently changed
     * first, in keyset batches; brands are looked up once per batch, as the list does per page. No transaction here:
     * each batch has its own.
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public RowSource<ProductView> vendorExport(CurrentUser user, String status, String q) {
        Specification<Product> filter = vendorFilter(user, status, q);
        CategoryTree tree = new CategoryTree(categories.findAll());
        return exports.specification(products, filter)
                .orderBy(ProductExports.SORT.resolve(null))
                .batches(batch -> {
                    Lookups lookups = lookups(batch, false, tree);
                    return batch.stream().map(p -> ProductMapper.toView(p, tree, lookups.brand(p))).toList();
                });
    }

    /** The vendor list's filters (shared by the list and its export): only the caller's own products. */
    private static Specification<Product> vendorFilter(CurrentUser user, String status, String q) {
        UUID vendorId = user.requireVendorId();
        ProductStatus statusFilter = BadRequestException.parseEnum(ProductStatus.class, "status", status);
        return filter(vendorId, null, statusFilter, q);
    }

    public ProductView vendorGet(CurrentUser user, UUID productId) {
        UUID vendorId = user.requireVendorId();
        Product product = products.findById(productId)
                .filter(p -> p.getVendorId().equals(vendorId))
                .orElseThrow(ProductQueryService::productNotFound);
        Lookups lookups = lookups(List.of(product), false);
        return ProductMapper.toView(product, lookups.tree, lookups.brand(product));
    }

    /** {@code categoryId} matches the category and all its sub-categories (like public search). */
    public PageResponse<AdminProductView> adminList(UUID vendorId, UUID categoryId, String status, String q,
                                                    Integer page, Integer size) {
        ProductStatus statusFilter = BadRequestException.parseEnum(ProductStatus.class, "status", status);
        CategoryTree tree = new CategoryTree(categories.findAll());
        Page<Product> found = products.findAll(adminFilter(vendorId, categoryId, statusFilter, q, tree),
                Pages.request(page, size, NEWEST_FIRST));
        Lookups lookups = lookups(found.getContent(), true, tree);
        return Pages.toResponse(found, p -> ProductMapper.toAdminView(p, tree, lookups.brand(p), lookups.vendor(p)));
    }

    /**
     * {@code GET /api/admin/products/export}: the admin list's filters, every page, most recently changed first or by
     * {@code sort}, in keyset batches; brands and vendor names are looked up once per batch, as the list does per
     * page. No transaction here: each batch has its own.
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public RowSource<AdminProductView> adminExport(UUID vendorId, UUID categoryId, String status, String q,
                                                   String sort) {
        ProductStatus statusFilter = BadRequestException.parseEnum(ProductStatus.class, "status", status);
        Order<Product> order = ProductExports.SORT.resolve(sort);
        CategoryTree tree = new CategoryTree(categories.findAll());
        return exports.specification(products, adminFilter(vendorId, categoryId, statusFilter, q, tree))
                .orderBy(order)
                .batches(batch -> {
                    Lookups lookups = lookups(batch, true, tree);
                    return batch.stream()
                            .map(p -> ProductMapper.toAdminView(p, tree, lookups.brand(p), lookups.vendor(p)))
                            .toList();
                });
    }

    /**
     * The admin list's filters (shared by the list and its export); {@code categoryId} matches the category and all
     * its sub-categories.
     */
    private static Specification<Product> adminFilter(UUID vendorId, UUID categoryId, ProductStatus status, String q,
                                                      CategoryTree tree) {
        List<UUID> categoryIds = null;
        if (categoryId != null) {
            categoryIds = tree.subtree(categoryId).stream().map(Category::getId).toList();
            if (categoryIds.isEmpty()) {
                categoryIds = List.of(categoryId);
            }
        }
        return filter(vendorId, categoryIds, status, q);
    }

    public AdminProductView adminGet(UUID productId) {
        Product product = products.findById(productId).orElseThrow(ProductQueryService::productNotFound);
        Lookups lookups = lookups(List.of(product), true);
        return ProductMapper.toAdminView(product, lookups.tree, lookups.brand(product), lookups.vendor(product));
    }

    public static NotFoundException productNotFound() {
        return new NotFoundException("PRODUCT_NOT_FOUND", "Product not found");
    }

    /** Optional filters; {@code q} = name contains q (case-insensitive) or SKU equals q. */
    static Specification<Product> filter(UUID vendorId, Collection<UUID> categoryIds, ProductStatus status, String q) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (vendorId != null) {
                predicates.add(cb.equal(root.get("vendorId"), vendorId));
            }
            if (categoryIds != null) {
                predicates.add(root.get("categoryId").in(categoryIds));
            }
            predicates.add(status == null
                    ? cb.notEqual(root.get("status"), ProductStatus.ARCHIVED)
                    : cb.equal(root.get("status"), status));
            if (q != null && !q.isBlank()) {
                String term = q.trim();
                String like = "%" + escapeLike(term.toLowerCase(Locale.ROOT)) + "%";
                predicates.add(cb.or(
                        cb.like(cb.lower(root.get("name")), like, LIKE_ESCAPE),
                        cb.equal(root.get("sku"), term)));
            }
            return cb.and(predicates.toArray(Predicate[]::new));
        };
    }

    private static String escapeLike(String value) {
        return value.replace("!", "!!").replace("%", "!%").replace("_", "!_");
    }

    private Lookups lookups(List<Product> page, boolean withVendors) {
        return lookups(page, withVendors, new CategoryTree(categories.findAll()));
    }

    /** Everything the views need, loaded with one query per table (no N+1). */
    private Lookups lookups(List<Product> page, boolean withVendors, CategoryTree tree) {
        Map<UUID, Brand> brandsById = brands.findAllById(distinct(page, Product::getBrandId)).stream()
                .collect(Collectors.toMap(Brand::getId, Function.identity()));
        Map<UUID, VendorRef> vendorsById = withVendors
                ? vendorRefs.findAllById(distinct(page, Product::getVendorId)).stream()
                .collect(Collectors.toMap(VendorRef::getVendorId, Function.identity()))
                : Map.of();
        return new Lookups(tree, brandsById, vendorsById);
    }

    private static List<UUID> distinct(List<Product> page, Function<Product, UUID> id) {
        return page.stream().map(id).filter(Objects::nonNull).distinct().toList();
    }

    private record Lookups(CategoryTree tree, Map<UUID, Brand> brands, Map<UUID, VendorRef> vendors) {
        Brand brand(Product p) {
            return p.getBrandId() == null ? null : brands.get(p.getBrandId());
        }

        VendorRef vendor(Product p) {
            return vendors.get(p.getVendorId());
        }
    }
}
