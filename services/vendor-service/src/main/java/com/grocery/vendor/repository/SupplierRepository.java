package com.grocery.vendor.repository;

import com.grocery.vendor.entity.Supplier;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SupplierRepository extends JpaRepository<Supplier, UUID> {

    /** The vendor's suppliers: active first, then by name. {@code active} null = all. */
    @Query("""
            select s from Supplier s
            where s.vendorId = :vendorId and (:active is null or s.active = :active)
            order by s.active desc, lower(s.name), s.id
            """)
    List<Supplier> findForVendor(@Param("vendorId") UUID vendorId, @Param("active") Boolean active);

    /** {@code SELECT … FOR UPDATE}: deactivating (then counting its open purchase orders) and editing a supplier. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from Supplier s where s.id = :id")
    Optional<Supplier> lockById(@Param("id") UUID id);

    /**
     * {@code SELECT … FOR SHARE}: a purchase order being created or edited names this supplier, so it cannot be
     * deactivated until that transaction ends (and a deactivation committed before is seen).
     */
    @Lock(LockModeType.PESSIMISTIC_READ)
    @Query("select s from Supplier s where s.id = :id")
    Optional<Supplier> lockShared(@Param("id") UUID id);
}
