package com.grocery.pricing.mapper;

import com.grocery.pricing.dto.PriceDtos.LivePrice;
import com.grocery.pricing.dto.PriceDtos.PriceChangeRequestView;
import com.grocery.pricing.dto.PriceDtos.PriceHistoryEntry;
import com.grocery.pricing.dto.PriceDtos.PriceView;
import com.grocery.pricing.entity.PriceChangeRequest;
import com.grocery.pricing.entity.PriceHistory;
import com.grocery.pricing.entity.ProductPrice;
import com.grocery.pricing.entity.ProductRef;
import com.grocery.pricing.event.PricingEvents.PriceChanged;
import com.grocery.pricing.service.PriceRules;

import java.math.BigDecimal;

/** Entities → API views and event payloads (plain Java). */
public final class PriceMapper {

    private PriceMapper() {
    }

    /** {@code price} and {@code pending} may be null. */
    public static PriceView toView(ProductRef product, ProductPrice price, PriceChangeRequest pending,
                                   BigDecimal thresholdPercent) {
        return new PriceView(product.getProductId(), product.getName(), product.getUnitLabel(), product.getSku(),
                product.getStatus().name(), price == null ? null : toLivePrice(price),
                pending == null ? null : toView(pending, product, false), thresholdPercent, null);
    }

    public static LivePrice toLivePrice(ProductPrice price) {
        return new LivePrice(price.getMrp(), price.getSellingPrice(), price.getCurrency(),
                PriceRules.discountPercent(price.getMrp(), price.getSellingPrice()), price.getUpdatedAt());
    }

    /** {@code product} may be null (unknown name); {@code withVendorName} only for admins. */
    public static PriceChangeRequestView toView(PriceChangeRequest request, ProductRef product, boolean withVendorName) {
        return new PriceChangeRequestView(request.getId(), request.getProductId(),
                product == null ? null : product.getName(), product == null ? null : product.getUnitLabel(),
                request.getVendorId(), withVendorName && product != null ? product.getVendorName() : null,
                request.getCurrentMrp(), request.getCurrentSellingPrice(),
                request.getRequestedMrp(), request.getRequestedSellingPrice(), request.getChangePercent(),
                request.getStatus().name(), request.getNote(), request.getRequestedBy(), request.getRequestedAt(),
                request.getDecidedBy(), request.getDecidedAt(), request.getDecisionReason());
    }

    public static PriceHistoryEntry toEntry(PriceHistory entry) {
        return new PriceHistoryEntry(entry.getId(), entry.getOldMrp(), entry.getOldSellingPrice(), entry.getNewMrp(),
                entry.getNewSellingPrice(), entry.getChangePercent(), entry.getSource().name(), entry.getRequestId(),
                entry.getChangedBy(), entry.getChangedAt());
    }

    public static PriceChanged toPriceChanged(ProductPrice price) {
        return new PriceChanged(price.getProductId(), price.getVendorId(), price.getMrp(), price.getSellingPrice(),
                price.getCurrency(), price.getPriceVersion(), price.getUpdatedAt());
    }
}
