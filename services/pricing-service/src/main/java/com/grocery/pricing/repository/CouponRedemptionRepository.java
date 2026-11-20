package com.grocery.pricing.repository;

import com.grocery.pricing.entity.CouponRedemption;
import com.grocery.pricing.entity.RedemptionStatus;
import com.grocery.pricing.entity.ReleaseReason;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CouponRedemptionRepository extends JpaRepository<CouponRedemption, UUID> {

    Optional<CouponRedemption> findByOrderId(UUID orderId);

    boolean existsByOrderId(UUID orderId);

    /** {@code FOR UPDATE}: the order-events consumer changes a redemption only while holding its row. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from CouponRedemption r where r.orderId = :orderId")
    Optional<CouponRedemption> lockByOrderId(@Param("orderId") UUID orderId);

    /** Uses that count towards the limits (RESERVED and CONFIRMED). */
    @Query("select count(r) from CouponRedemption r where r.couponId = :couponId and r.status <> :released")
    long countActive(@Param("couponId") UUID couponId, @Param("released") RedemptionStatus released);

    @Query("""
            select count(r) from CouponRedemption r
            where r.couponId = :couponId and r.customerId = :customerId and r.status <> :released
            """)
    long countActiveByCustomer(@Param("couponId") UUID couponId, @Param("customerId") UUID customerId,
                               @Param("released") RedemptionStatus released);

    /**
     * Usage statistics of several coupons: rows {@code [couponId, status, count, sum(discount),
     * sum(deliveryFeeDiscount)]}, one per coupon and status present.
     */
    @Query("""
            select r.couponId, r.status, count(r), sum(r.discount), sum(r.deliveryFeeDiscount)
            from CouponRedemption r where r.couponId in :couponIds
            group by r.couponId, r.status
            """)
    List<Object[]> usageStats(@Param("couponIds") Collection<UUID> couponIds);

    /** Rows {@code [couponId, count]} of one customer's active uses of the given coupons. */
    @Query("""
            select r.couponId, count(r) from CouponRedemption r
            where r.couponId in :couponIds and r.customerId = :customerId and r.status <> :released
            group by r.couponId
            """)
    List<Object[]> countActiveByCustomer(@Param("couponIds") Collection<UUID> couponIds,
                                         @Param("customerId") UUID customerId,
                                         @Param("released") RedemptionStatus released);

    Page<CouponRedemption> findByCouponId(UUID couponId, Pageable pageable);

    Page<CouponRedemption> findByCouponIdAndStatus(UUID couponId, RedemptionStatus status, Pageable pageable);

    /**
     * The sweeper's claim: up to {@code limit} RESERVED redemptions reserved before {@code cutoff} (no OrderCreated
     * came), oldest first, locked with {@code FOR UPDATE SKIP LOCKED} — rows another replica's sweeper or the
     * order-events consumer holds are skipped, so replicas never wait or collide. Served by
     * {@code idx_coupon_redemption_status_reserved}. Ids as text.
     */
    @Query(value = """
            SELECT BIN_TO_UUID(id) FROM coupon_redemption
            WHERE status = 'RESERVED' AND reserved_at < :cutoff
            ORDER BY reserved_at
            LIMIT :limit
            FOR UPDATE SKIP LOCKED""", nativeQuery = true)
    List<String> claimExpired(@Param("cutoff") Instant cutoff, @Param("limit") int limit);

    /** Releases claimed (locked) redemptions that are still RESERVED. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update CouponRedemption r set r.status = :released, r.releasedAt = :now, r.releaseReason = :reason
            where r.id in :ids and r.status = :reserved
            """)
    int release(@Param("ids") Collection<UUID> ids, @Param("now") Instant now,
                @Param("reason") ReleaseReason reason, @Param("released") RedemptionStatus released,
                @Param("reserved") RedemptionStatus reserved);
}
