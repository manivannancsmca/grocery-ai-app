package com.grocery.pricing.repository;

import com.grocery.pricing.entity.ProductRef;
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

public interface ProductRefRepository extends JpaRepository<ProductRef, UUID>, JpaSpecificationExecutor<ProductRef> {

    /** Whether the id is a vendor known from the catalog (Phase 12: a coupon's store type). */
    boolean existsByVendorId(UUID vendorId);

    /** Rows {@code [vendorId, vendorName]} of the known vendors among {@code vendorIds} (Phase 12: coupon views). */
    @Query("""
            select r.vendorId, max(r.vendorName) from ProductRef r
            where r.vendorId in :vendorIds and r.vendorName is not null group by r.vendorId
            """)
    List<Object[]> findVendorNames(@Param("vendorIds") Collection<UUID> vendorIds);

    /**
     * {@code SELECT … FOR UPDATE}: the first statement of every transaction that changes a price or a
     * price-change request (see {@code PriceWriter}). When the row does not exist yet, InnoDB's gap lock
     * blocks its insertion just the same.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from ProductRef r where r.productId = :productId")
    Optional<ProductRef> lockById(@Param("productId") UUID productId);
}
