package com.grocery.vendor.service;

import com.grocery.common.id.Ids;
import com.grocery.common.security.CurrentUser;
import com.grocery.vendor.dto.PurchasingDtos.SupplierRequest;
import com.grocery.vendor.dto.PurchasingDtos.SupplierView;
import com.grocery.vendor.entity.PurchaseOrderStatus;
import com.grocery.vendor.entity.Supplier;
import com.grocery.vendor.mapper.PurchasingMapper;
import com.grocery.vendor.repository.PurchaseOrderRepository;
import com.grocery.vendor.repository.SupplierRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The signed-in vendor's suppliers (Phase 12 §4). The store comes from the JWT's {@code vendor_id} claim; another
 * vendor's or an unknown supplier is 404 {@code SUPPLIER_NOT_FOUND}. Delete = deactivate.
 *
 * <p><b>Locking rule.</b> Deactivating (by DELETE or by {@code active: false}) locks the supplier row
 * ({@code FOR UPDATE}) before counting its open purchase orders; creating or editing a purchase order reads the supplier
 * with {@code FOR SHARE} (see {@link PurchaseOrderService}). Either the purchase order commits first and the
 * deactivation sees it (409 {@code SUPPLIER_IN_USE}), or the deactivation commits first and the purchase order sees an
 * inactive supplier (422 {@code INVALID_SUPPLIER}).
 */
@Service
public class SupplierService {

    private static final Logger log = LoggerFactory.getLogger(SupplierService.class);

    private final SupplierRepository suppliers;
    private final PurchaseOrderRepository purchaseOrders;
    private final Clock clock;

    public SupplierService(SupplierRepository suppliers, PurchaseOrderRepository purchaseOrders, Clock clock) {
        this.suppliers = suppliers;
        this.purchaseOrders = purchaseOrders;
        this.clock = clock;
    }

    /** {@code active}: null = all (active first, then by name). */
    @Transactional(readOnly = true)
    public List<SupplierView> list(CurrentUser user, Boolean active) {
        UUID vendorId = user.requireVendorId();
        return suppliers.findForVendor(vendorId, active).stream().map(PurchasingMapper::toView).toList();
    }

    @Transactional(readOnly = true)
    public SupplierView get(CurrentUser user, UUID supplierId) {
        UUID vendorId = user.requireVendorId();
        return PurchasingMapper.toView(suppliers.findById(supplierId).filter(s -> s.isOwnedBy(vendorId))
                .orElseThrow(PurchasingException::supplierNotFound));
    }

    @Transactional
    public SupplierView create(CurrentUser user, SupplierRequest request) {
        UUID vendorId = user.requireVendorId();
        Supplier supplier = Supplier.create(Ids.newId(), vendorId, details(request),
                request.active() == null || request.active(), clock.instant());
        suppliers.saveAndFlush(supplier);
        log.info("Vendor {} added supplier {} ({})", vendorId, supplier.getId(), supplier.getName());
        return PurchasingMapper.toView(supplier);
    }

    /** Replaces every field; {@code active} null keeps it, {@code false} deactivates (409 with open POs). */
    @Transactional
    public SupplierView update(CurrentUser user, UUID supplierId, SupplierRequest request) {
        UUID vendorId = user.requireVendorId();
        Supplier supplier = lockOwn(vendorId, supplierId);
        boolean active = request.active() == null ? supplier.isActive() : request.active();
        if (supplier.isActive() && !active) {
            requireNoOpenPurchaseOrders(supplier);
        }
        supplier.update(details(request), active, clock.instant());
        suppliers.flush();
        return PurchasingMapper.toView(supplier);
    }

    /** DELETE = deactivate; an inactive supplier stays inactive (204 again). */
    @Transactional
    public void deactivate(CurrentUser user, UUID supplierId) {
        UUID vendorId = user.requireVendorId();
        Supplier supplier = lockOwn(vendorId, supplierId);
        if (!supplier.isActive()) {
            return;
        }
        requireNoOpenPurchaseOrders(supplier);
        supplier.deactivate(clock.instant());
        log.info("Vendor {} deactivated supplier {}", vendorId, supplierId);
    }

    /** Called under the supplier's row lock: every purchase order committed before is counted. */
    private void requireNoOpenPurchaseOrders(Supplier supplier) {
        long open = purchaseOrders.countBySupplier(supplier.getId(), PurchaseOrderStatus.OPEN);
        if (open > 0) {
            throw PurchasingException.supplierInUse(open);
        }
    }

    private Supplier lockOwn(UUID vendorId, UUID supplierId) {
        return suppliers.lockById(supplierId).filter(s -> s.isOwnedBy(vendorId))
                .orElseThrow(PurchasingException::supplierNotFound);
    }

    private static Supplier.Details details(SupplierRequest request) {
        String gstin = clean(request.gstin());
        return new Supplier.Details(request.name().trim(), clean(request.contactName()), clean(request.phone()),
                clean(request.email()), gstin, clean(request.address()), request.leadTimeDays(),
                clean(request.notes()));
    }

    private static String clean(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
