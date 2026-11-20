package com.grocery.pricing.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Every {@code grocery.pricing.coupons.sweep-interval} (1 min, fixed delay) releases the RESERVED redemptions that got
 * no {@code OrderCreated} within {@code COUPON_RESERVATION_TTL} (30 min): order-service redeemed, then never saved the
 * order. Scheduled only with {@code grocery.pricing.coupons.sweeper-enabled} (default true, see
 * {@code PricingConfig}); safe in every replica ({@code FOR UPDATE SKIP LOCKED}).
 */
@Component
public class CouponReservationSweeper {

    private static final Logger log = LoggerFactory.getLogger(CouponReservationSweeper.class);

    private final CouponRedemptionService redemptions;

    public CouponReservationSweeper(CouponRedemptionService redemptions) {
        this.redemptions = redemptions;
    }

    @Scheduled(initialDelayString = "${grocery.pricing.coupons.sweep-interval:1m}",
            fixedDelayString = "${grocery.pricing.coupons.sweep-interval:1m}")
    public void sweep() {
        try {
            redemptions.releaseExpired();
        } catch (RuntimeException e) {
            log.warn("Coupon sweeper run failed, retried in the next run: {}", e.toString());
        }
    }
}
