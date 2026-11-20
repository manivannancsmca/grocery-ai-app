package com.grocery.vendor.repository;

import com.grocery.vendor.entity.ProductRef;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface ProductRefRepository extends JpaRepository<ProductRef, UUID> {

    /** {@code SELECT … FOR UPDATE} on one product copy (the projection's first read). */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from ProductRef p where p.productId = :productId")
    Optional<ProductRef> lockById(@Param("productId") UUID productId);
}
