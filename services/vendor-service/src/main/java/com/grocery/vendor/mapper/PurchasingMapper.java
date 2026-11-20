package com.grocery.vendor.mapper;

import com.grocery.vendor.dto.PurchasingDtos.LineView;
import com.grocery.vendor.dto.PurchasingDtos.PurchaseOrderView;
import com.grocery.vendor.dto.PurchasingDtos.ReceiptLineView;
import com.grocery.vendor.dto.PurchasingDtos.ReceiptView;
import com.grocery.vendor.dto.PurchasingDtos.SupplierView;
import com.grocery.vendor.entity.PurchaseOrder;
import com.grocery.vendor.entity.PurchaseReceipt;
import com.grocery.vendor.entity.Supplier;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** Supplier / PurchaseOrder → views (plain Java; call inside the reading transaction: lines and receipts are lazy). */
public final class PurchasingMapper {

    private PurchasingMapper() {
    }

    public static SupplierView toView(Supplier supplier) {
        return new SupplierView(supplier.getId(), supplier.getName(), supplier.getContactName(), supplier.getPhone(),
                supplier.getEmail(), supplier.getGstin(), supplier.getAddress(), supplier.getLeadTimeDays(),
                supplier.getNotes(), supplier.isActive(), supplier.getCreatedAt(), supplier.getUpdatedAt());
    }

    public static PurchaseOrderView toView(PurchaseOrder order) {
        return new PurchaseOrderView(order.getId(), order.getNumber(), order.getVendorId(),
                order.getSupplier().getId(), order.getSupplier().getName(), order.getStatus().name(),
                order.getExpectedAt(), order.getNotes(), PurchaseOrder.CURRENCY,
                order.getLines().stream().map(line -> new LineView(line.getProductId(), line.getProductName(),
                        line.getOrderedQuantity(), line.getReceivedQuantity(), line.openQuantity(),
                        money(line.getUnitCost()), money(line.lineTotal()))).toList(),
                order.orderedUnits(), order.receivedUnits(), money(order.totalCost()), money(order.receivedCost()),
                order.getReceipts().stream().map(PurchasingMapper::toView).toList(),
                order.getCreatedAt(), order.getUpdatedAt(), order.getSentAt(), order.getCancelledAt(),
                order.getClosedAt(), order.getFullyReceivedAt());
    }

    static ReceiptView toView(PurchaseReceipt receipt) {
        return new ReceiptView(receipt.getId(), receipt.getReceivedAt(), receipt.getReceivedBy(), receipt.getNote(),
                receipt.getLines().stream().map(line -> new ReceiptLineView(line.getProductId(), line.getProductName(),
                        line.getQuantity(), money(line.getUnitCost()), money(line.cost()))).toList(),
                receipt.units(), money(receipt.totalCost()));
    }

    /** Money with exactly 2 decimals (sums of DECIMAL(12,2) values never need rounding). */
    public static BigDecimal money(BigDecimal value) {
        return value.setScale(2, RoundingMode.UNNECESSARY);
    }
}
