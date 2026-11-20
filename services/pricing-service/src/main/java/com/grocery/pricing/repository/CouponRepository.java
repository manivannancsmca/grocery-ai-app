package com.grocery.pricing.repository;

import com.grocery.pricing.entity.Coupon;
import com.grocery.pricing.entity.CouponScope;
import com.grocery.pricing.entity.CouponStoreType;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CouponRepository extends JpaRepository<Coupon, UUID>, JpaSpecificationExecutor<Coupon> {

    Optional<Coupon> findByCode(String code);

    boolean existsByCode(String code);

    boolean existsByCodeAndIdNot(String code, UUID id);

    /**
     * {@code SELECT … FOR UPDATE} by code: the FIRST statement of every redeem transaction. Every redemption of a
     * coupon is created while holding this lock, so counting its uses after the lock sees every committed one.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from Coupon c where c.code = :code")
    Optional<Coupon> lockByCode(@Param("code") String code);

    /**
     * Customer offers: active, public coupons valid now, for a store of {@code storeTypes} (PLATFORM) or exactly
     * {@code storeId} (STORE; null = none), ending soonest first.
     */
    @Query("""
            select c from Coupon c
            where c.active = true and c.publicOffer = true and c.validFrom <= :now and c.validUntil > :now
              and ((c.scope = :platform and c.storeType in :storeTypes)
                   or (c.scope = :store and c.storeId = :storeId))
            order by c.validUntil, c.code
            """)
    List<Coupon> findOffers(@Param("now") Instant now, @Param("platform") CouponScope platform,
                            @Param("storeTypes") Collection<CouponStoreType> storeTypes,
                            @Param("store") CouponScope store, @Param("storeId") UUID storeId);
}
