package com.grocery.vendor.service;

import com.grocery.common.data.Pages;
import com.grocery.common.export.ExportQueries;
import com.grocery.common.export.RowSource;
import com.grocery.common.id.Ids;
import com.grocery.common.security.CurrentUser;
import com.grocery.common.web.BadRequestException;
import com.grocery.common.web.PageResponse;
import com.grocery.vendor.config.FulfillmentProperties;
import com.grocery.vendor.dto.PurchasingDtos.LineRequest;
import com.grocery.vendor.dto.PurchasingDtos.PurchaseOrderRequest;
import com.grocery.vendor.dto.PurchasingDtos.PurchaseOrderView;
import com.grocery.vendor.dto.PurchasingExports;
import com.grocery.vendor.entity.ProductRef;
import com.grocery.vendor.entity.ProductStatus;
import com.grocery.vendor.entity.PurchaseOrder;
import com.grocery.vendor.entity.PurchaseOrderLine;
import com.grocery.vendor.entity.PurchaseOrderStatus;
import com.grocery.vendor.entity.Supplier;
import com.grocery.vendor.mapper.PurchasingMapper;
import com.grocery.vendor.repository.ProductRefRepository;
import com.grocery.vendor.repository.PurchaseOrderRepository;
import com.grocery.vendor.repository.SupplierRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Purchase orders (Phase 12 §4): the vendor's list, detail, create / edit (DRAFT only), send, cancel and close, and
 * the admins' read-only list. Receiving is {@link PurchaseReceivingService}.
 *
 * <h2>Locking rule</h2>
 * Every change of an existing purchase order — edit, send, cancel, close, receive — first locks its row
 * ({@code SELECT … FOR UPDATE}), then checks ownership (another vendor's → 404) and the status (409), then changes it, in
 * one transaction; two changes of one purchase order are applied one after the other, each seeing the other's result.
 * Create and edit read the supplier with {@code FOR SHARE} (see {@link SupplierService}). Lock waits that time out
 * become 409 {@code CONCURRENT_MODIFICATION} (grocery-common).
 *
 * <h2>Lines</h2>
 * 1–100 lines, each product at most once (400 {@code VALIDATION_FAILED}), quantity 1–100000, unit cost ≥ 0 with at most
 * 2 decimals. Each product must be the caller's own and not archived, as known from {@code catalog.events}
 * ({@code product_ref}); otherwise 422 {@code INVALID_PRODUCT}. The product name is snapshotted on the line.
 */
@Service
public class PurchaseOrderService {

    private static final Logger log = LoggerFactory.getLogger(PurchaseOrderService.class);
    private static final int NUMBER_ATTEMPTS = 5;

    private final PurchaseOrderRepository purchaseOrders;
    private final SupplierRepository suppliers;
    private final ProductRefRepository products;
    private final PurchaseOrderNumbers numbers;
    private final ExportQueries exports;
    private final ZoneId zone;
    private final Clock clock;

    public PurchaseOrderService(PurchaseOrderRepository purchaseOrders, SupplierRepository suppliers,
                                ProductRefRepository products, PurchaseOrderNumbers numbers, ExportQueries exports,
                                FulfillmentProperties properties, Clock clock) {
        this.purchaseOrders = purchaseOrders;
        this.suppliers = suppliers;
        this.products = products;
        this.numbers = numbers;
        this.exports = exports;
        this.zone = properties.zone();
        this.clock = clock;
    }

    // ---- reads --------------------------------------------------------------------------------------------

    /**
     * Newest first. {@code status}: blank = all, {@code OPEN} (DRAFT, SENT, PARTIALLY_RECEIVED) or one status;
     * {@code number}: exact purchase order number (case-insensitive). Bad values → 400 INVALID_PARAMETER.
     */
    @Transactional(readOnly = true)
    public PageResponse<PurchaseOrderView> list(CurrentUser user, String status, UUID supplierId, String number,
                                                Integer page, Integer size) {
        UUID vendorId = user.requireVendorId();
        return Pages.toResponse(purchaseOrders.search(vendorId, statuses(status), supplierId, cleanNumber(number),
                Pages.request(page, size, Sort.unsorted())), PurchasingMapper::toView);
    }

    /**
     * {@code GET /api/vendor/purchase-orders/export}: the caller's own purchase orders (the vendor id is the caller's,
     * from the JWT) with the list's filters, every page, newest first. No transaction here: each batch has its own.
     */
    public RowSource<PurchaseOrderView> listExport(CurrentUser user, String status, UUID supplierId, String number) {
        UUID vendorId = user.requireVendorId();
        return exports.jpql(PurchaseOrder.class, "p", PurchaseOrderRepository.VENDOR_SEARCH)
                .param("vendorId", vendorId)
                .param("statuses", statuses(status))
                .param("supplierId", supplierId)
                .param("number", cleanNumber(number))
                .orderBy(PurchasingExports.PURCHASE_ORDER_SORT.resolve(null))
                .rows(PurchasingMapper::toView);
    }

    @Transactional(readOnly = true)
    public PurchaseOrderView get(CurrentUser user, UUID purchaseOrderId) {
        UUID vendorId = user.requireVendorId();
        return PurchasingMapper.toView(purchaseOrders.findByIdAndVendorId(purchaseOrderId, vendorId)
                .orElseThrow(PurchasingException::purchaseOrderNotFound));
    }

    /** Admins: every vendor's purchase orders, or one vendor's ({@code vendorId}), newest first. */
    @Transactional(readOnly = true)
    public PageResponse<PurchaseOrderView> adminList(UUID vendorId, String status, Integer page, Integer size) {
        return Pages.toResponse(purchaseOrders.searchAll(vendorId, statuses(status),
                Pages.request(page, size, Sort.unsorted())), PurchasingMapper::toView);
    }

    /**
     * {@code GET /api/admin/purchase-orders/export}: the admin list's filters on every page, newest first or by
     * {@code sort} ({@code number} | {@code created}, {@code ,asc} | {@code ,desc}), in keyset batches. No transaction
     * here: each batch has its own.
     */
    public RowSource<PurchaseOrderView> adminListExport(UUID vendorId, String status, String sort) {
        return exports.jpql(PurchaseOrder.class, "p", PurchaseOrderRepository.ADMIN_SEARCH)
                .param("vendorId", vendorId)
                .param("statuses", statuses(status))
                .orderBy(PurchasingExports.PURCHASE_ORDER_SORT.resolve(sort))
                .rows(PurchasingMapper::toView);
    }

    @Transactional(readOnly = true)
    public PurchaseOrderView adminGet(UUID purchaseOrderId) {
        return PurchasingMapper.toView(purchaseOrders.findById(purchaseOrderId)
                .orElseThrow(PurchasingException::purchaseOrderNotFound));
    }

    // ---- changes ------------------------------------------------------------------------------------------

    /** A new DRAFT with a fresh {@code PO-XXXXXXXX} number. */
    @Transactional
    public PurchaseOrderView create(CurrentUser user, PurchaseOrderRequest request) {
        UUID vendorId = user.requireVendorId();
        Instant expectedAt = expectedAt(request.expectedAt());
        Supplier supplier = orderableSupplier(vendorId, request.supplierId());
        List<PurchaseOrderLine> lines = lines(vendorId, request.lines());
        Instant now = clock.instant();
        PurchaseOrder order = PurchaseOrder.draft(Ids.newId(), freeNumber(), vendorId, supplier, expectedAt,
                clean(request.notes()), lines, user.userId(), now);
        purchaseOrders.saveAndFlush(order);
        log.info("Vendor {} drafted purchase order {} ({} line(s)) for supplier {}", vendorId, order.getNumber(),
                lines.size(), supplier.getId());
        return PurchasingMapper.toView(order);
    }

    /** DRAFT only (else 409 PURCHASE_ORDER_NOT_EDITABLE): replaces supplier, expectedAt, notes and the lines. */
    @Transactional
    public PurchaseOrderView update(CurrentUser user, UUID purchaseOrderId, PurchaseOrderRequest request) {
        UUID vendorId = user.requireVendorId();
        Instant expectedAt = expectedAt(request.expectedAt());
        PurchaseOrder order = lockOwn(vendorId, purchaseOrderId);
        if (order.getStatus() != PurchaseOrderStatus.DRAFT) {
            throw PurchasingException.notEditable(order.getStatus());
        }
        Supplier supplier = orderableSupplier(vendorId, request.supplierId());
        List<PurchaseOrderLine> lines = lines(vendorId, request.lines());
        order.edit(supplier, expectedAt, clean(request.notes()), lines, clock.instant());
        purchaseOrders.flush();
        return PurchasingMapper.toView(order);
    }

    /** DRAFT → SENT. */
    @Transactional
    public PurchaseOrderView send(CurrentUser user, UUID purchaseOrderId) {
        PurchaseOrder order = lockOwn(user.requireVendorId(), purchaseOrderId);
        requireStatus(order, PurchaseOrderStatus.SENDABLE, "be sent");
        order.send(clock.instant());
        log.info("Purchase order {} sent to supplier {}", order.getNumber(), order.getSupplier().getId());
        return flushed(order);
    }

    /** DRAFT / SENT → CANCELLED. */
    @Transactional
    public PurchaseOrderView cancel(CurrentUser user, UUID purchaseOrderId) {
        PurchaseOrder order = lockOwn(user.requireVendorId(), purchaseOrderId);
        requireStatus(order, PurchaseOrderStatus.CANCELLABLE, "be cancelled");
        order.cancel(clock.instant());
        log.info("Purchase order {} cancelled", order.getNumber());
        return flushed(order);
    }

    /** PARTIALLY_RECEIVED → CLOSED (short-closed: the rest will not come). */
    @Transactional
    public PurchaseOrderView close(CurrentUser user, UUID purchaseOrderId) {
        PurchaseOrder order = lockOwn(user.requireVendorId(), purchaseOrderId);
        requireStatus(order, PurchaseOrderStatus.CLOSABLE, "be closed");
        order.close(clock.instant());
        log.info("Purchase order {} closed short ({} of {} unit(s) received)", order.getNumber(),
                order.receivedUnits(), order.orderedUnits());
        return flushed(order);
    }

    // ---- helpers ------------------------------------------------------------------------------------------

    /**
     * The caller's own purchase order, locked ({@code FOR UPDATE}, inside the caller's transaction); another vendor's
     * or an unknown one → 404.
     */
    public PurchaseOrder lockOwn(UUID vendorId, UUID purchaseOrderId) {
        return purchaseOrders.lockById(purchaseOrderId)
                .filter(order -> order.getVendorId().equals(vendorId))
                .orElseThrow(PurchasingException::purchaseOrderNotFound);
    }

    static void requireStatus(PurchaseOrder order, Set<PurchaseOrderStatus> allowed, String action) {
        if (!allowed.contains(order.getStatus())) {
            throw PurchasingException.invalidTransition(order.getStatus(), action);
        }
    }

    private PurchaseOrderView flushed(PurchaseOrder order) {
        purchaseOrders.flush();
        return PurchasingMapper.toView(order);
    }

    /** The caller's active supplier, read with FOR SHARE; anything else → 422 INVALID_SUPPLIER. */
    private Supplier orderableSupplier(UUID vendorId, UUID supplierId) {
        Supplier supplier = suppliers.lockShared(supplierId).filter(s -> s.isOwnedBy(vendorId))
                .orElseThrow(() -> PurchasingException.invalidSupplier(supplierId, "is not one of your suppliers"));
        if (!supplier.isActive()) {
            throw PurchasingException.invalidSupplier(supplierId, "is inactive");
        }
        return supplier;
    }

    /** Checks the lines (each product once, own and not archived) and snapshots the product names. */
    private List<PurchaseOrderLine> lines(UUID vendorId, List<LineRequest> requested) {
        Map<UUID, Integer> firstIndex = new HashMap<>();
        for (int i = 0; i < requested.size(); i++) {
            Integer previous = firstIndex.putIfAbsent(requested.get(i).productId(), i);
            if (previous != null) {
                throw PurchasingException.validation("lines[" + i + "].productId",
                        "the product is already on lines[" + previous + "]");
            }
        }
        Map<UUID, ProductRef> known = products.findAllById(firstIndex.keySet()).stream()
                .collect(Collectors.toMap(ProductRef::getProductId, Function.identity()));
        List<PurchaseOrderLine> lines = new ArrayList<>(requested.size());
        for (LineRequest line : requested) {
            ProductRef product = known.get(line.productId());
            if (product == null || !product.getVendorId().equals(vendorId)) {
                throw PurchasingException.invalidProduct(line.productId(), "is not one of your products");
            }
            if (product.getStatus() == ProductStatus.ARCHIVED) {
                throw PurchasingException.invalidProduct(line.productId(), "is archived");
            }
            lines.add(new PurchaseOrderLine(product.getProductId(), product.getName(), line.orderedQuantity(),
                    line.unitCost().setScale(2, RoundingMode.UNNECESSARY)));
        }
        return lines;
    }

    /** A number no purchase order has yet (the unique key is the final guarantee). */
    private String freeNumber() {
        for (int attempt = 1; attempt <= NUMBER_ATTEMPTS; attempt++) {
            String number = numbers.next();
            if (!purchaseOrders.existsByNumber(number)) {
                return number;
            }
            log.warn("Purchase order number {} is taken; drawing another (attempt {})", number, attempt);
        }
        throw new IllegalStateException("No free purchase order number after " + NUMBER_ATTEMPTS + " attempts");
    }

    /**
     * {@code expectedAt}: blank → null; an ISO-8601 instant (an offset is converted to UTC); or a date
     * {@code yyyy-MM-dd} = 00:00 of that day in India time ({@code grocery.fulfillment.timezone}). Microseconds.
     */
    public Instant expectedAt(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String text = value.trim();
        try {
            return Instant.parse(text).truncatedTo(ChronoUnit.MICROS);
        } catch (DateTimeParseException notAnInstant) {
            try {
                return LocalDate.parse(text).atStartOfDay(zone).toInstant();
            } catch (DateTimeParseException notADate) {
                throw PurchasingException.validation("expectedAt",
                        "must be an ISO-8601 instant (2026-11-20T04:30:00Z) or a date (2026-11-20)");
            }
        }
    }

    /** {@code number} query parameter: an exact purchase order number, case-insensitive; blank = any. */
    private static String cleanNumber(String number) {
        return number == null || number.isBlank() ? null : number.trim().toUpperCase(Locale.ROOT);
    }

    /** {@code status} query parameter: blank = all, {@code OPEN}, or one status; anything else 400. */
    static Set<PurchaseOrderStatus> statuses(String status) {
        if (status == null || status.isBlank()) {
            return EnumSet.allOf(PurchaseOrderStatus.class);
        }
        if ("OPEN".equals(status.trim())) {
            return PurchaseOrderStatus.OPEN;
        }
        return EnumSet.of(BadRequestException.parseEnum(PurchaseOrderStatus.class, "status", status));
    }

    private static String clean(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
