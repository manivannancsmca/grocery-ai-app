package com.grocery.vendor.repository;

import com.grocery.vendor.entity.Vendor;
import com.grocery.vendor.entity.VendorStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface VendorRepository extends JpaRepository<Vendor, UUID> {

    Optional<Vendor> findByOwnerUserId(UUID ownerUserId);

    boolean existsByOwnerUserId(UUID ownerUserId);

    /** The admin search's filters, shared by the list and its export (alias {@code v}). */
    String ADMIN_SEARCH = """
            from Vendor v
            where (:status is null or v.status = :status)
              and (:query is null or lower(v.businessName) like :query
                   or lower(v.legalName) like :query or lower(v.contactEmail) like :query)
            """;

    /** Admin search; filters optional. {@code query} is lower-cased with % wildcards. */
    @Query("select v " + ADMIN_SEARCH)
    Page<Vendor> search(@Param("status") VendorStatus status, @Param("query") String query, Pageable pageable);

    /**
     * The store directory's prefilter: vendors of {@code status} whose store lies in the box (bounds inclusive).
     * Vendors without coordinates never match. The exact distance is computed by the caller.
     */
    @Query("""
            select v from Vendor v
            where v.status = :status
              and v.address.latitude between :minLatitude and :maxLatitude
              and v.address.longitude between :minLongitude and :maxLongitude
            """)
    List<Vendor> findInBox(@Param("status") VendorStatus status,
                           @Param("minLatitude") double minLatitude, @Param("maxLatitude") double maxLatitude,
                           @Param("minLongitude") double minLongitude, @Param("maxLongitude") double maxLongitude);
}
