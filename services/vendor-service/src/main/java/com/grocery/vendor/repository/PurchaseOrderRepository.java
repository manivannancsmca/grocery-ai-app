package com.grocery.vendor.repository;

import com.grocery.vendor.entity.PurchaseOrder;
import com.grocery.vendor.entity.PurchaseOrderStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.Optional;
import java.util.UUID;

public interface PurchaseOrderRepository extends JpaRepository<PurchaseOrder, UUID> {

    /** {@code SELECT … FOR UPDATE} on one purchase order: the first read of every change (edit, actions, receive). */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from PurchaseOrder p where p.id = :id")
    Optional<PurchaseOrder> lockById(@Param("id") UUID id);

    Optional<PurchaseOrder> findByIdAndVendorId(UUID id, UUID vendorId);

    boolean existsByNumber(String number);

    /** The vendor's list filters, shared by the list, its count and its export (alias {@code p}). */
    String VENDOR_SEARCH = """
            from PurchaseOrder p
            where p.vendorId = :vendorId and p.status in :statuses
              and (:supplierId is null or p.supplier.id = :supplierId)
              and (:number is null or p.number = :number)
            """;

    /**
     * The vendor's list, newest first; every filter optional ({@code statuses} never empty). Served by
     * {@code idx_purchase_order_vendor_created}.
     */
    @Query(value = "select p " + VENDOR_SEARCH + " order by p.createdAt desc, p.id desc",
            countQuery = "select count(p) " + VENDOR_SEARCH)
    Page<PurchaseOrder> search(@Param("vendorId") UUID vendorId,
                               @Param("statuses") Collection<PurchaseOrderStatus> statuses,
                               @Param("supplierId") UUID supplierId, @Param("number") String number,
                               Pageable pageable);

    /** The admin list's filters, shared by the list, its count and its export (alias {@code p}). */
    String ADMIN_SEARCH = """
            from PurchaseOrder p
            where (:vendorId is null or p.vendorId = :vendorId) and p.status in :statuses
            """;

    /** Admin list (read-only): every vendor or one, newest first. */
    @Query(value = "select p " + ADMIN_SEARCH + " order by p.createdAt desc, p.id desc",
            countQuery = "select count(p) " + ADMIN_SEARCH)
    Page<PurchaseOrder> searchAll(@Param("vendorId") UUID vendorId,
                                  @Param("statuses") Collection<PurchaseOrderStatus> statuses, Pageable pageable);

    /** Open purchase orders naming a supplier (a supplier with any cannot be deactivated). */
    @Query("select count(p) from PurchaseOrder p where p.supplier.id = :supplierId and p.status in :statuses")
    long countBySupplier(@Param("supplierId") UUID supplierId,
                         @Param("statuses") Collection<PurchaseOrderStatus> statuses);
}
