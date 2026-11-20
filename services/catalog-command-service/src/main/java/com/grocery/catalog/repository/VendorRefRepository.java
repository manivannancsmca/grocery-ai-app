package com.grocery.catalog.repository;

import com.grocery.catalog.entity.VendorRef;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface VendorRefRepository extends JpaRepository<VendorRef, UUID> {

    /** {@code FOR UPDATE}: the vendor-events consumer changes the projection under this lock. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select v from VendorRef v where v.vendorId = :vendorId")
    Optional<VendorRef> lockForUpdate(@Param("vendorId") UUID vendorId);

    /**
     * {@code FOR SHARE}: product commands read the vendor's name/status under this lock, so a concurrent
     * vendor event waits for them (and then re-snapshots their product with a higher version).
     * When the row does not exist yet, InnoDB's gap lock blocks its insertion just the same.
     */
    @Lock(LockModeType.PESSIMISTIC_READ)
    @Query("select v from VendorRef v where v.vendorId = :vendorId")
    Optional<VendorRef> lockForShare(@Param("vendorId") UUID vendorId);
}
