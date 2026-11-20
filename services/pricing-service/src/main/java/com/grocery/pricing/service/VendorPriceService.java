package com.grocery.pricing.service;

import com.grocery.common.id.Ids;
import com.grocery.common.security.CurrentUser;
import com.grocery.common.web.BusinessRuleException;
import com.grocery.common.web.ConflictException;
import com.grocery.pricing.config.PricingProperties;
import com.grocery.pricing.dto.PriceDtos;
import com.grocery.pricing.dto.PriceDtos.PriceChangeRequestView;
import com.grocery.pricing.dto.PriceDtos.PriceRequest;
import com.grocery.pricing.dto.PriceDtos.PriceView;
import com.grocery.pricing.entity.PriceChangeRequest;
import com.grocery.pricing.entity.PriceChangeStatus;
import com.grocery.pricing.entity.PriceSource;
import com.grocery.pricing.entity.ProductPrice;
import com.grocery.pricing.entity.ProductRef;
import com.grocery.pricing.event.PricingEvents;
import com.grocery.pricing.event.PricingEvents.PriceChangeCancelled;
import com.grocery.pricing.event.PricingEvents.PriceChangeRequested;
import com.grocery.pricing.mapper.PriceMapper;
import com.grocery.pricing.repository.PriceChangeRequestRepository;
import com.grocery.pricing.repository.ProductPriceRepository;
import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The vendor's price commands (contract §3). Ownership comes from the token's {@code vendor_id} claim; another
 * vendor's product or request is reported as not found. Every command follows the locking rule documented on
 * {@link PriceWriter}.
 */
@Service
public class VendorPriceService {

    static final String CANCELLED_BY_VENDOR = "Cancelled by vendor";

    private final PriceWriter writer;
    private final ProductPriceRepository prices;
    private final PriceChangeRequestRepository requests;
    private final EntityManager entityManager;
    private final BigDecimal thresholdPercent;
    private final Clock clock;

    public VendorPriceService(PriceWriter writer, ProductPriceRepository prices, PriceChangeRequestRepository requests,
                              EntityManager entityManager, PricingProperties properties, Clock clock) {
        this.writer = writer;
        this.prices = prices;
        this.requests = requests;
        this.entityManager = entityManager;
        this.thresholdPercent = properties.approvalThresholdPercent();
        this.clock = clock;
    }

    /**
     * Sets the price: the first price and changes within the threshold apply at once ({@code outcome APPLIED});
     * a larger change creates a PENDING request and leaves the live price alone ({@code PENDING_APPROVAL}).
     */
    public PriceView setPrice(CurrentUser user, UUID productId, PriceRequest request) {
        UUID vendorId = user.requireVendorId();
        BigDecimal mrp = PriceRules.money(request.mrp());
        BigDecimal sellingPrice = PriceRules.money(request.sellingPrice());
        if (sellingPrice.compareTo(mrp) > 0) {
            throw new BusinessRuleException("SELLING_PRICE_ABOVE_MRP", "The selling price cannot be above the MRP",
                    List.of(Map.of("field", "sellingPrice", "message", "must not be greater than mrp")));
        }
        String note = blankToNull(request.note());

        return writer.inProductTransaction(productId, product -> {
            requireOwned(product, vendorId);
            product.requireNotArchived();
            ProductPrice current = prices.findById(productId).orElse(null);
            if (requests.findPending(productId, PriceChangeStatus.PENDING).isPresent()) {
                throw new ConflictException("PRICE_CHANGE_PENDING",
                        "A price change for this product is waiting for approval; cancel it first");
            }
            if (current == null) {
                ProductPrice first = writer.apply(product, null, mrp, sellingPrice, PriceSource.VENDOR, null,
                        user.userId());
                return PriceMapper.toView(product, first, null, thresholdPercent).withOutcome(PriceDtos.APPLIED);
            }
            if (current.isSameAs(mrp, sellingPrice)) {
                throw new ConflictException("PRICE_UNCHANGED", "The price is already " + mrp + " / " + sellingPrice);
            }
            BigDecimal changePercent = PriceRules.changePercent(current.getSellingPrice(), sellingPrice);
            if (!PriceRules.needsApproval(changePercent, thresholdPercent)) {
                writer.apply(product, current, mrp, sellingPrice, PriceSource.VENDOR, null, user.userId());
                return PriceMapper.toView(product, current, null, thresholdPercent).withOutcome(PriceDtos.APPLIED);
            }

            PriceChangeRequest pending = PriceChangeRequest.pending(Ids.newId(), product, current, mrp, sellingPrice,
                    changePercent, note, user.userId(), clock.instant());
            entityManager.persist(pending);
            entityManager.flush();
            writer.publish(productId, PricingEvents.PRICE_CHANGE_REQUESTED, new PriceChangeRequested(pending.getId(),
                    productId, product.getVendorId(), product.getName(), current.getMrp(), current.getSellingPrice(),
                    mrp, sellingPrice, changePercent, user.userId()));
            return PriceMapper.toView(product, current, pending, thresholdPercent)
                    .withOutcome(PriceDtos.PENDING_APPROVAL);
        });
    }

    /** The vendor withdraws a pending request. Not pending → 409 PRICE_CHANGE_NOT_PENDING. */
    public PriceChangeRequestView cancel(CurrentUser user, UUID requestId) {
        UUID vendorId = user.requireVendorId();
        return writer.inRequestTransaction(requestId, vendorId, (product, request) -> {
            request.cancel(user.userId(), CANCELLED_BY_VENDOR, clock.instant());
            entityManager.flush();
            writer.publish(product.getProductId(), PricingEvents.PRICE_CHANGE_CANCELLED, new PriceChangeCancelled(
                    request.getId(), product.getProductId(), request.getVendorId(), user.userId(), CANCELLED_BY_VENDOR));
            return PriceMapper.toView(request, product, false);
        });
    }

    private static void requireOwned(ProductRef product, UUID vendorId) {
        if (product == null || !product.isOwnedBy(vendorId)) {
            throw PriceWriter.productNotFound();
        }
    }

    static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
