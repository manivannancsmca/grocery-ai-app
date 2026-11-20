package com.grocery.pricing.service;

import com.grocery.common.data.Pages;
import com.grocery.common.export.ExportQueries;
import com.grocery.common.export.ExportSort;
import com.grocery.common.export.RowSource;
import com.grocery.common.security.CurrentUser;
import com.grocery.common.web.BadRequestException;
import com.grocery.common.web.PageResponse;
import com.grocery.pricing.config.PricingProperties;
import com.grocery.pricing.dto.PriceDtos.PriceChangeRequestView;
import com.grocery.pricing.dto.PriceDtos.PriceHistoryEntry;
import com.grocery.pricing.dto.PriceDtos.PriceSummary;
import com.grocery.pricing.dto.PriceDtos.PriceView;
import com.grocery.pricing.dto.PriceExports;
import com.grocery.pricing.entity.PriceChangeRequest;
import com.grocery.pricing.entity.PriceChangeStatus;
import com.grocery.pricing.entity.ProductPrice;
import com.grocery.pricing.entity.ProductRef;
import com.grocery.pricing.entity.ProductStatus;
import com.grocery.pricing.mapper.PriceMapper;
import com.grocery.pricing.repository.PriceChangeRequestRepository;
import com.grocery.pricing.repository.PriceHistoryRepository;
import com.grocery.pricing.repository.ProductPriceRepository;
import com.grocery.pricing.repository.ProductRefRepository;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Price lists, details, history and price-change requests for vendors and admins, straight from MySQL.
 * Vendor lists leave archived products out and are sorted by product name. The exports read the same lists in their
 * own short transactions (see {@link ExportQueries}), so they opt out of the class-level one.
 */
@Service
@Transactional(readOnly = true)
public class PriceQueryService {

    /** {@code filter} of {@code GET /api/vendor/prices}. */
    public enum PriceFilter {
        PRICED,
        UNPRICED,
        PENDING_APPROVAL
    }

    private static final Sort BY_NAME = Sort.by("name").and(Sort.by("productId"));
    private static final Sort NEWEST_REQUEST_FIRST =
            Sort.by(Sort.Direction.DESC, "requestedAt").and(Sort.by(Sort.Direction.DESC, "id"));
    private static final Sort OLDEST_REQUEST_FIRST =
            Sort.by(Sort.Direction.ASC, "requestedAt").and(Sort.by(Sort.Direction.ASC, "id"));
    private static final Sort NEWEST_CHANGE_FIRST =
            Sort.by(Sort.Direction.DESC, "changedAt").and(Sort.by(Sort.Direction.DESC, "id"));
    private static final char LIKE_ESCAPE = '!';

    private final ProductRefRepository products;
    private final ProductPriceRepository prices;
    private final PriceChangeRequestRepository requests;
    private final PriceHistoryRepository history;
    private final BigDecimal thresholdPercent;
    private final ExportQueries exports;

    public PriceQueryService(ProductRefRepository products, ProductPriceRepository prices,
                             PriceChangeRequestRepository requests, PriceHistoryRepository history,
                             PricingProperties properties, ExportQueries exports) {
        this.products = products;
        this.prices = prices;
        this.requests = requests;
        this.history = history;
        this.thresholdPercent = properties.approvalThresholdPercent();
        this.exports = exports;
    }

    // ---- vendor ----

    public PageResponse<PriceView> vendorList(CurrentUser user, String q, String filter, Integer page, Integer size) {
        Page<ProductRef> found = products.findAll(vendorPriceFilter(user, q, filter),
                Pages.request(page, size, BY_NAME));
        return Pages.toResponse(found, priceViews(found.getContent()));
    }

    /**
     * {@code GET /api/vendor/prices/export}: the list's search and filter on every page, by product name, in keyset
     * batches; the prices and pending requests are looked up once per batch, as the list does per page.
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public RowSource<PriceView> vendorListExport(CurrentUser user, String q, String filter) {
        return exports.specification(products, vendorPriceFilter(user, q, filter))
                .orderBy(PriceExports.PRICE_SORT.resolve(null))
                .batches(batch -> batch.stream().map(priceViews(batch)).toList());
    }

    /** Counts of the vendor's non-archived products, with the same semantics as the list filters. */
    public PriceSummary vendorSummary(CurrentUser user) {
        UUID vendorId = user.requireVendorId();
        long all = products.count(vendorProducts(vendorId, null, null));
        long priced = products.count(vendorProducts(vendorId, PriceFilter.PRICED, null));
        long pending = products.count(vendorProducts(vendorId, PriceFilter.PENDING_APPROVAL, null));
        return new PriceSummary(all, priced, all - priced, pending);
    }

    public PriceView vendorGet(CurrentUser user, UUID productId) {
        ProductRef product = ownedProduct(user, productId);
        return PriceMapper.toView(product, prices.findById(productId).orElse(null),
                requests.findPending(productId, PriceChangeStatus.PENDING).orElse(null), thresholdPercent);
    }

    public PageResponse<PriceHistoryEntry> vendorHistory(CurrentUser user, UUID productId, Integer page, Integer size) {
        ownedProduct(user, productId);
        return Pages.toResponse(history.findByProductId(productId, Pages.request(page, size, NEWEST_CHANGE_FIRST)),
                PriceMapper::toEntry);
    }

    public PageResponse<PriceChangeRequestView> vendorRequests(CurrentUser user, String status, Integer page,
                                                               Integer size) {
        Page<PriceChangeRequest> found = requests.findAll(vendorRequestFilter(user, status),
                Pages.request(page, size, NEWEST_REQUEST_FIRST));
        return toViews(found, false);
    }

    /**
     * {@code GET /api/vendor/price-change-requests/export}: the vendor's own requests with the list's status filter,
     * newest first, in keyset batches; the products are looked up once per batch, as the list does per page.
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public RowSource<PriceChangeRequestView> vendorRequestsExport(CurrentUser user, String status) {
        return exports.specification(requests, vendorRequestFilter(user, status))
                .orderBy(PriceExports.NEWEST_SORT.resolve(null))
                .batches(batch -> batch.stream().map(requestViews(batch, false)).toList());
    }

    // ---- admin ----

    /** Default {@code status=PENDING}, oldest first (the queue); other statuses newest first. */
    public PageResponse<PriceChangeRequestView> adminList(String status, UUID vendorId, Integer page, Integer size) {
        PriceChangeStatus statusFilter = adminStatus(status);
        Sort sort = statusFilter == PriceChangeStatus.PENDING ? OLDEST_REQUEST_FIRST : NEWEST_REQUEST_FIRST;
        Page<PriceChangeRequest> found = requests.findAll(requestFilter(vendorId, statusFilter),
                Pages.request(page, size, sort));
        return toViews(found, true);
    }

    /**
     * {@code GET /api/admin/price-change-requests/export}: the queue's filters on every page, in the list's order (the
     * PENDING queue oldest first, other statuses newest first) or by {@code sort}, in keyset batches.
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public RowSource<PriceChangeRequestView> adminListExport(String status, UUID vendorId, String sort) {
        PriceChangeStatus statusFilter = adminStatus(status);
        ExportSort<PriceChangeRequest> order = statusFilter == PriceChangeStatus.PENDING
                ? PriceExports.PENDING_SORT : PriceExports.NEWEST_SORT;
        return exports.specification(requests, requestFilter(vendorId, statusFilter))
                .orderBy(order.resolve(sort))
                .batches(batch -> batch.stream().map(requestViews(batch, true)).toList());
    }

    public PriceChangeRequestView adminGet(UUID requestId) {
        PriceChangeRequest request = requests.findById(requestId).orElseThrow(PriceWriter::requestNotFound);
        return PriceMapper.toView(request, products.findById(request.getProductId()).orElse(null), true);
    }

    // ---- helpers ----

    private ProductRef ownedProduct(CurrentUser user, UUID productId) {
        UUID vendorId = user.requireVendorId();
        return products.findById(productId)
                .filter(p -> p.isOwnedBy(vendorId))
                .orElseThrow(PriceWriter::productNotFound);
    }

    private PageResponse<PriceChangeRequestView> toViews(Page<PriceChangeRequest> found, boolean withVendorName) {
        return Pages.toResponse(found, requestViews(found.getContent(), withVendorName));
    }

    /** The views of one page (or export batch) of requests, with their products looked up in one query. */
    private Function<PriceChangeRequest, PriceChangeRequestView> requestViews(List<PriceChangeRequest> found,
                                                                              boolean withVendorName) {
        List<UUID> productIds = found.stream().map(PriceChangeRequest::getProductId).distinct().toList();
        Map<UUID, ProductRef> productsById = byId(products.findAllById(productIds), ProductRef::getProductId);
        return r -> PriceMapper.toView(r, productsById.get(r.getProductId()), withVendorName);
    }

    /** The views of one page (or export batch) of products, with their prices and pending requests (a query each). */
    private Function<ProductRef, PriceView> priceViews(List<ProductRef> found) {
        List<UUID> ids = found.stream().map(ProductRef::getProductId).toList();
        Map<UUID, ProductPrice> pricesById = byId(prices.findAllById(ids), ProductPrice::getProductId);
        Map<UUID, PriceChangeRequest> pendingById = byId(
                requests.findByProductIdInAndStatus(ids, PriceChangeStatus.PENDING), PriceChangeRequest::getProductId);
        return product -> PriceMapper.toView(product, pricesById.get(product.getProductId()),
                pendingById.get(product.getProductId()), thresholdPercent);
    }

    /** The vendor price list's filters (shared by the list and its export): only the caller's own products. */
    private static Specification<ProductRef> vendorPriceFilter(CurrentUser user, String q, String filter) {
        UUID vendorId = user.requireVendorId();
        PriceFilter priceFilter = BadRequestException.parseEnum(PriceFilter.class, "filter", filter);
        return vendorProducts(vendorId, priceFilter, q);
    }

    /** The vendor's request list filters (shared by the list and its export): only the caller's own requests. */
    private static Specification<PriceChangeRequest> vendorRequestFilter(CurrentUser user, String status) {
        UUID vendorId = user.requireVendorId();
        return requestFilter(vendorId, BadRequestException.parseEnum(PriceChangeStatus.class, "status", status));
    }

    /** The admin queue's status (shared by the list and its export): PENDING unless another one is asked for. */
    private static PriceChangeStatus adminStatus(String status) {
        PriceChangeStatus statusFilter = BadRequestException.parseEnum(PriceChangeStatus.class, "status", status);
        return statusFilter == null ? PriceChangeStatus.PENDING : statusFilter;
    }

    /** The vendor's non-archived products; {@code q} = name contains (case-insensitive) or SKU equals. */
    static Specification<ProductRef> vendorProducts(UUID vendorId, PriceFilter filter, String q) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(cb.equal(root.get("vendorId"), vendorId));
            predicates.add(cb.notEqual(root.get("status"), ProductStatus.ARCHIVED));
            if (q != null && !q.isBlank()) {
                String term = q.trim();
                String like = "%" + escapeLike(term.toLowerCase(Locale.ROOT)) + "%";
                predicates.add(cb.or(
                        cb.like(cb.lower(root.get("name")), like, LIKE_ESCAPE),
                        cb.equal(root.get("sku"), term)));
            }
            if (filter != null) {
                predicates.add(switch (filter) {
                    case PRICED -> cb.exists(priceOf(root, query, cb));
                    case UNPRICED -> cb.not(cb.exists(priceOf(root, query, cb)));
                    case PENDING_APPROVAL -> cb.exists(pendingRequestOf(root, query, cb));
                });
            }
            return cb.and(predicates.toArray(Predicate[]::new));
        };
    }

    private static Subquery<UUID> priceOf(Root<ProductRef> product, CriteriaQuery<?> query, CriteriaBuilder cb) {
        Subquery<UUID> subquery = query.subquery(UUID.class);
        Root<ProductPrice> price = subquery.from(ProductPrice.class);
        return subquery.select(price.get("productId"))
                .where(cb.equal(price.get("productId"), product.get("productId")));
    }

    private static Subquery<UUID> pendingRequestOf(Root<ProductRef> product, CriteriaQuery<?> query,
                                                   CriteriaBuilder cb) {
        Subquery<UUID> subquery = query.subquery(UUID.class);
        Root<PriceChangeRequest> request = subquery.from(PriceChangeRequest.class);
        return subquery.select(request.get("id"))
                .where(cb.equal(request.get("productId"), product.get("productId")),
                        cb.equal(request.get("status"), PriceChangeStatus.PENDING));
    }

    private static Specification<PriceChangeRequest> requestFilter(UUID vendorId, PriceChangeStatus status) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (vendorId != null) {
                predicates.add(cb.equal(root.get("vendorId"), vendorId));
            }
            if (status != null) {
                predicates.add(cb.equal(root.get("status"), status));
            }
            return cb.and(predicates.toArray(Predicate[]::new));
        };
    }

    private static String escapeLike(String value) {
        return value.replace("!", "!!").replace("%", "!%").replace("_", "!_");
    }

    private static <T> Map<UUID, T> byId(Collection<T> items, Function<T, UUID> id) {
        return items.stream().collect(Collectors.toMap(id, Function.identity()));
    }
}
