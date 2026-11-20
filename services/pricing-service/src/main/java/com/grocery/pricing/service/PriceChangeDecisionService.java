package com.grocery.pricing.service;

import com.grocery.common.security.CurrentUser;
import com.grocery.pricing.dto.PriceDtos.PriceChangeRequestView;
import com.grocery.pricing.entity.PriceSource;
import com.grocery.pricing.entity.ProductPrice;
import com.grocery.pricing.event.PricingEvents;
import com.grocery.pricing.event.PricingEvents.PriceChangeApproved;
import com.grocery.pricing.event.PricingEvents.PriceChangeRejected;
import com.grocery.pricing.mapper.PriceMapper;
import com.grocery.pricing.repository.ProductPriceRepository;
import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.UUID;

/**
 * Admin decisions on price-change requests (contract §4), under the locking rule of {@link PriceWriter}: two
 * admins deciding the same request at once are serialised by the product lock, and the second one finds the
 * request decided (409 PRICE_CHANGE_NOT_PENDING).
 */
@Service
public class PriceChangeDecisionService {

    private final PriceWriter writer;
    private final ProductPriceRepository prices;
    private final EntityManager entityManager;
    private final Clock clock;

    public PriceChangeDecisionService(PriceWriter writer, ProductPriceRepository prices, EntityManager entityManager,
                                      Clock clock) {
        this.writer = writer;
        this.prices = prices;
        this.entityManager = entityManager;
        this.clock = clock;
    }

    /** The requested price becomes the live price (history source APPROVED_REQUEST, next priceVersion). */
    public PriceChangeRequestView approve(UUID requestId, String note, CurrentUser admin) {
        String decisionNote = VendorPriceService.blankToNull(note);
        return writer.inRequestTransaction(requestId, null, (product, request) -> {
            request.approve(admin.userId(), decisionNote, clock.instant());     // 409 when no longer pending
            product.requireNotArchived();
            ProductPrice current = prices.findById(product.getProductId())
                    .orElseThrow(() -> new IllegalStateException("Request " + requestId + " without a live price"));
            entityManager.flush();
            writer.apply(product, current, request.getRequestedMrp(), request.getRequestedSellingPrice(),
                    PriceSource.APPROVED_REQUEST, request.getId(), admin.userId());
            writer.publish(product.getProductId(), PricingEvents.PRICE_CHANGE_APPROVED, new PriceChangeApproved(
                    request.getId(), product.getProductId(), request.getVendorId(), admin.userId()));
            return PriceMapper.toView(request, product, true);
        });
    }

    public PriceChangeRequestView reject(UUID requestId, String reason, CurrentUser admin) {
        String decisionReason = reason.trim();
        return writer.inRequestTransaction(requestId, null, (product, request) -> {
            request.reject(admin.userId(), decisionReason, clock.instant());
            entityManager.flush();
            writer.publish(product.getProductId(), PricingEvents.PRICE_CHANGE_REJECTED, new PriceChangeRejected(
                    request.getId(), product.getProductId(), request.getVendorId(), admin.userId(), decisionReason));
            return PriceMapper.toView(request, product, true);
        });
    }
}
