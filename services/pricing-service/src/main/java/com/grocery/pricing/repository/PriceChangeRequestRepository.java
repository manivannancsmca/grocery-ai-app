package com.grocery.pricing.repository;

import com.grocery.pricing.entity.PriceChangeRequest;
import com.grocery.pricing.entity.PriceChangeStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PriceChangeRequestRepository
        extends JpaRepository<PriceChangeRequest, UUID>, JpaSpecificationExecutor<PriceChangeRequest> {

    /** [productId, vendorId] of a request. Both never change, so this may be read before taking any lock. */
    @Query("select r.productId, r.vendorId from PriceChangeRequest r where r.id = :id")
    List<Object[]> findOwnerById(@Param("id") UUID id);

    /** {@code FOR UPDATE}; taken only after the product's {@code product_ref} lock. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from PriceChangeRequest r where r.id = :id")
    Optional<PriceChangeRequest> lockById(@Param("id") UUID id);

    /** The product's pending request (at most one, enforced by the unique pending_key). */
    @Query("select r from PriceChangeRequest r where r.productId = :productId and r.status = :pending")
    Optional<PriceChangeRequest> findPending(@Param("productId") UUID productId,
                                             @Param("pending") PriceChangeStatus pending);

    /** Same, {@code FOR UPDATE}; taken only after the product's {@code product_ref} lock. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from PriceChangeRequest r where r.productId = :productId and r.status = :pending")
    Optional<PriceChangeRequest> lockPending(@Param("productId") UUID productId,
                                             @Param("pending") PriceChangeStatus pending);

    List<PriceChangeRequest> findByProductIdInAndStatus(Collection<UUID> productIds, PriceChangeStatus status);
}
