package com.grocery.pricing.service;

import com.grocery.common.id.Ids;
import com.grocery.common.idempotency.ProcessedEventGuard;
import com.grocery.common.security.CurrentUser;
import com.grocery.pricing.config.CouponProperties;
import com.grocery.pricing.dto.CouponDtos;
import com.grocery.pricing.dto.CouponDtos.EvaluateRequest;
import com.grocery.pricing.dto.CouponDtos.EvaluateResponse;
import com.grocery.pricing.dto.CouponDtos.RedeemRequest;
import com.grocery.pricing.dto.CouponDtos.RedeemResponse;
import com.grocery.pricing.entity.Coupon;
import com.grocery.pricing.entity.CouponRedemption;
import com.grocery.pricing.entity.CouponTerms;
import com.grocery.pricing.entity.OrderType;
import com.grocery.pricing.entity.RedemptionStatus;
import com.grocery.pricing.entity.ReleaseReason;
import com.grocery.pricing.repository.CouponRedemptionRepository;
import com.grocery.pricing.repository.CouponRepository;
import jakarta.persistence.EntityManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Using coupons (contract §2): {@code evaluate} for the cart, {@code redeem} for order placement, and the redemption
 * lifecycle — CONFIRMED on {@code OrderCreated}, RELEASED on {@code OrderCancelled} / {@code OrderRejected}, and
 * RELEASED by the sweeper when no {@code OrderCreated} came within {@code COUPON_RESERVATION_TTL}.
 *
 * <h2>Usage limits under concurrency</h2>
 * A redemption is only ever created by {@link #redeem}, in a READ COMMITTED transaction whose <b>first statement
 * locks the coupon's row</b> ({@code SELECT … FOR UPDATE}). Counting the RESERVED + CONFIRMED uses, checking the
 * limits and inserting the new RESERVED row all happen while holding that lock, so two redeems of one coupon are
 * serialised and the later one counts the earlier one: a limit of 1 lets exactly one of many concurrent orders
 * through. Releases (consumer, sweeper) only lower the counts and need not take the coupon lock. The unique
 * {@code order_id} makes redeem idempotent per order and is the backstop against a concurrent redeem of the same order
 * with another coupon.
 */
@Service
public class CouponRedemptionService {

    public static final String CONSUMER = "pricing-service.order-events";
    /** Upper bound of sweeper batches per run, so one run never holds the scheduler thread for long. */
    static final int MAX_BATCHES_PER_RUN = 100;
    private static final Logger log = LoggerFactory.getLogger(CouponRedemptionService.class);

    private final CouponRepository coupons;
    private final CouponRedemptionRepository redemptions;
    private final ProcessedEventGuard processedEvents;
    private final EntityManager entityManager;
    private final TransactionTemplate readCommitted;
    private final Duration reservationTtl;
    private final int sweepBatchSize;
    private final CouponMetrics metrics;
    private final Clock clock;

    public CouponRedemptionService(CouponRepository coupons, CouponRedemptionRepository redemptions,
                                   ProcessedEventGuard processedEvents, EntityManager entityManager,
                                   PlatformTransactionManager transactionManager, CouponProperties properties,
                                   CouponMetrics metrics, Clock clock) {
        this.coupons = coupons;
        this.redemptions = redemptions;
        this.processedEvents = processedEvents;
        this.entityManager = entityManager;
        this.readCommitted = new TransactionTemplate(transactionManager);
        this.readCommitted.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        this.reservationTtl = properties.reservationTtl();
        this.sweepBatchSize = properties.sweepBatchSize();
        this.metrics = metrics;
        this.clock = clock;
    }

    // ---------------------------------------------------------------- evaluate (cart)

    /**
     * Whether the coupon can be used for this cart now, and what it takes off. Never an error for a coupon problem:
     * {@code valid: false} with the reason. Reads only (the counts may be a moment old; {@link #redeem} decides).
     */
    @Transactional(readOnly = true)
    public EvaluateResponse evaluate(EvaluateRequest request, CurrentUser caller) {
        UUID customerId = customer(request.customerId(), caller);
        String code = CouponRules.normalizeCode(request.code());
        BigDecimal subtotal = CouponRules.money(request.subtotal());
        BigDecimal deliveryFee = request.deliveryFee() == null ? CouponRules.ZERO
                : CouponRules.money(request.deliveryFee());
        Coupon coupon = coupons.findByCode(code).orElse(null);
        if (coupon == null) {
            return new EvaluateResponse(false, CouponRules.ZERO, CouponRules.ZERO, CouponDtos.COUPON_NOT_FOUND,
                    null, code, null, null, null);
        }
        CouponTerms terms = coupon.terms();
        String reason = reason(coupon, terms, clock.instant(), customerId, request.storeId(), request.orderType(),
                subtotal);
        if (reason != null) {
            return invalid(terms, reason);
        }
        CouponRules.Amounts amounts = CouponRules.amounts(terms, subtotal, deliveryFee);
        return new EvaluateResponse(true, amounts.discount(), amounts.deliveryFeeDiscount(), null, null, terms.code(),
                terms.scope().name(), terms.storeId(), terms.discountType().name());
    }

    // ---------------------------------------------------------------- redeem (order placement)

    /**
     * Reserves one use of the coupon for {@code orderId} (before order-service saves the order). Idempotent per
     * order: the same order, coupon and customer get the same redemption back while it is RESERVED or CONFIRMED.
     * A coupon that cannot be used → 409 COUPON_NOT_APPLICABLE with {@code reason}; the order already redeemed
     * with another coupon or customer, or released → 409 COUPON_REDEMPTION_CONFLICT. Counted in
     * {@code grocery_coupon_redemptions_total} as RESERVED (a new use) or REFUSED (either 409).
     */
    public RedeemResponse redeem(RedeemRequest request, CurrentUser caller) {
        UUID customerId = customer(request.customerId(), caller);
        try {
            return reserve(request, customerId);
        } catch (CouponException refused) {
            metrics.count(CouponMetrics.Outcome.REFUSED, 1);
            throw refused;
        }
    }

    private RedeemResponse reserve(RedeemRequest request, UUID customerId) {
        String code = CouponRules.normalizeCode(request.code());
        BigDecimal subtotal = CouponRules.money(request.subtotal());
        BigDecimal deliveryFee = request.deliveryFee() == null ? CouponRules.ZERO
                : CouponRules.money(request.deliveryFee());
        try {
            return readCommitted.execute(status -> {
                // FIRST statement: the coupon's row lock (see the class comment)
                Coupon coupon = coupons.lockByCode(code)
                        .orElseThrow(() -> CouponException.notApplicable(CouponDtos.COUPON_NOT_FOUND, code, null));
                CouponRedemption existing = redemptions.findByOrderId(request.orderId()).orElse(null);
                if (existing != null) {
                    return replay(existing, coupon, customerId);
                }
                CouponTerms terms = coupon.terms();
                Instant now = clock.instant();
                String reason = reason(coupon, terms, now, customerId, request.storeId(), request.orderType(),
                        subtotal);
                if (reason != null) {
                    throw CouponException.notApplicable(reason, terms.code(),
                            CouponDtos.COUPON_MIN_ORDER.equals(reason) ? terms.minOrderSubtotal() : null);
                }
                CouponRules.Amounts amounts = CouponRules.amounts(terms, subtotal, deliveryFee);
                CouponRedemption redemption = CouponRedemption.reserve(Ids.newId(), request.orderId(), coupon,
                        customerId, request.storeId(), request.orderType(), subtotal, deliveryFee,
                        amounts.discount(), amounts.deliveryFeeDiscount(), now);
                entityManager.persist(redemption);
                redemptions.flush();
                metrics.count(CouponMetrics.Outcome.RESERVED, 1);               // after the commit
                log.info("Coupon {} reserved for order {} (customer {}): discount {}, delivery fee discount {}",
                        terms.code(), request.orderId(), customerId, amounts.discount(),
                        amounts.deliveryFeeDiscount());
                return response(redemption, coupon);
            });
        } catch (DataIntegrityViolationException e) {
            // the same order was redeemed concurrently with another coupon (unique order_id)
            CouponRedemption existing = redemptions.findByOrderId(request.orderId()).orElseThrow(() -> e);
            Coupon coupon = coupons.findByCode(code).orElseThrow(() -> e);
            return replay(existing, coupon, customerId);
        }
    }

    private static RedeemResponse replay(CouponRedemption existing, Coupon coupon, UUID customerId) {
        if (!existing.getCouponId().equals(coupon.getId()) || !existing.getCustomerId().equals(customerId)) {
            throw CouponException.redemptionConflict("Order " + existing.getOrderId()
                    + " already redeemed coupon " + existing.getCouponCode());
        }
        if (existing.getStatus() == RedemptionStatus.RELEASED) {
            throw CouponException.redemptionConflict("The coupon use of order " + existing.getOrderId()
                    + " was already released (" + existing.getReleaseReason() + ")");
        }
        return response(existing, coupon);
    }

    /** The redemption's amounts, with the coupon's current name and who funds it (PLATFORM or STORE: its scope). */
    private static RedeemResponse response(CouponRedemption redemption, Coupon coupon) {
        CouponTerms terms = coupon.terms();
        return new RedeemResponse(redemption.getId(), redemption.getDiscount(), redemption.getDeliveryFeeDiscount(),
                terms.name(), terms.scope().name());
    }

    // ---------------------------------------------------------------- order.events

    /**
     * {@code OrderCreated}: the order was saved → its redemption (if any) becomes CONFIRMED. Idempotent per event
     * ({@code processed_event}) and by state. Orders without a redemption leave no trace.
     *
     * @return true when a redemption changed
     */
    @Transactional
    public boolean confirm(String eventId, UUID orderId, String orderNumber) {
        if (!redemptions.existsByOrderId(orderId) || !processedEvents.markProcessed(eventId, CONSUMER)) {
            return false;
        }
        CouponRedemption redemption = redemptions.lockByOrderId(orderId).orElse(null);
        if (redemption != null) {
            redemption.learnOrderNumber(orderNumber);
        }
        if (redemption == null || !redemption.confirm(clock.instant())) {
            log.debug("OrderCreated {} for order {}: redemption already {}", eventId, orderId,
                    redemption == null ? "gone" : redemption.getStatus());
            return false;
        }
        log.info("Coupon {} confirmed for order {}", redemption.getCouponCode(), orderId);
        metrics.count(CouponMetrics.Outcome.CONFIRMED, 1);
        return true;
    }

    /** {@code OrderCancelled} / {@code OrderRejected}: the use is given back (RELEASED). Idempotent as {@link #confirm}. */
    @Transactional
    public boolean release(String eventId, UUID orderId, String orderNumber, ReleaseReason reason) {
        if (!redemptions.existsByOrderId(orderId) || !processedEvents.markProcessed(eventId, CONSUMER)) {
            return false;
        }
        CouponRedemption redemption = redemptions.lockByOrderId(orderId).orElse(null);
        if (redemption != null) {
            redemption.learnOrderNumber(orderNumber);
        }
        if (redemption == null || !redemption.release(reason, clock.instant())) {
            return false;
        }
        log.info("Coupon {} released for order {} ({})", redemption.getCouponCode(), orderId, reason);
        metrics.count(CouponMetrics.Outcome.RELEASED, 1);
        return true;
    }

    // ---------------------------------------------------------------- sweeper

    /**
     * Releases every RESERVED redemption older than the reservation TTL (no {@code OrderCreated}: the order was never
     * saved), in batches claimed with {@code FOR UPDATE SKIP LOCKED}, each its own transaction — safe in every
     * replica, and never waiting for a row the consumer holds.
     *
     * @return how many redemptions were released
     */
    public int releaseExpired() {
        Instant now = clock.instant();
        Instant cutoff = now.minus(reservationTtl);
        int released = 0;
        for (int batch = 0; batch < MAX_BATCHES_PER_RUN; batch++) {
            int[] result = readCommitted.execute(status -> {
                List<UUID> ids = redemptions.claimExpired(cutoff, sweepBatchSize).stream()
                        .map(UUID::fromString).toList();
                int changed = ids.isEmpty() ? 0 : redemptions.release(ids, now, ReleaseReason.RESERVATION_EXPIRED,
                        RedemptionStatus.RELEASED, RedemptionStatus.RESERVED);
                metrics.count(CouponMetrics.Outcome.RELEASED, changed);
                return new int[]{ids.size(), changed};
            });
            released += result[1];
            if (result[0] < sweepBatchSize) {
                break;
            }
        }
        if (released > 0) {
            log.info("Coupon sweeper: released {} reservation(s) without OrderCreated since before {}", released,
                    cutoff);
        }
        return released;
    }

    // ---------------------------------------------------------------- shared

    /** Every reason but COUPON_NOT_FOUND; the usage counts are read only when the terms are fine. */
    private String reason(Coupon coupon, CouponTerms terms, Instant now, UUID customerId, UUID storeId,
                          OrderType orderType, BigDecimal subtotal) {
        String reason = CouponRules.termsReason(terms, now, storeId, orderType, subtotal);
        if (reason != null) {
            return reason;
        }
        long used = terms.totalUsageLimit() == null ? 0
                : redemptions.countActive(coupon.getId(), RedemptionStatus.RELEASED);
        long usedByCustomer = redemptions.countActiveByCustomer(coupon.getId(), customerId, RedemptionStatus.RELEASED);
        return CouponRules.limitReason(terms, used, usedByCustomer);
    }

    private static EvaluateResponse invalid(CouponTerms terms, String reason) {
        return new EvaluateResponse(false, CouponRules.ZERO, CouponRules.ZERO, reason,
                CouponDtos.COUPON_MIN_ORDER.equals(reason) ? terms.minOrderSubtotal() : null, terms.code(),
                terms.scope().name(), terms.storeId(), terms.discountType().name());
    }

    /** The customer of an internal call: the caller (forwarded customer token); another id → 403 CUSTOMER_MISMATCH. */
    private static UUID customer(UUID requested, CurrentUser caller) {
        if (requested != null && !requested.equals(caller.userId())) {
            throw CouponException.customerMismatch();
        }
        return caller.userId();
    }
}
