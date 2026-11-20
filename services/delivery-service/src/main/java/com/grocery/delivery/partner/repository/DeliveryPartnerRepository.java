package com.grocery.delivery.partner.repository;

import com.grocery.delivery.partner.entity.DeliveryPartner;
import com.grocery.delivery.partner.entity.PartnerStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Partners are keyed by their user id, so "my profile" is a plain findById(userId). */
public interface DeliveryPartnerRepository extends JpaRepository<DeliveryPartner, UUID> {

    /** The admin search's filters, shared by the list and its export (alias {@code p}). */
    String ADMIN_SEARCH = """
            from DeliveryPartner p
            where (:status is null or p.status = :status)
              and (:query is null or lower(p.fullName) like :query or lower(p.email) like :query
                   or p.phone like :query or lower(p.city) like :query)
            """;

    /** Admin search; filters optional. {@code query} is lower-cased with % wildcards. */
    @Query("select p " + ADMIN_SEARCH)
    Page<DeliveryPartner> search(@Param("status") PartnerStatus status, @Param("query") String query, Pageable pageable);

    // ---- Phase 9 (dispatch) ----------------------------------------------------------------------------------

    /**
     * {@code SELECT … FOR UPDATE} on one partner (primary key: a record lock). Taken AFTER any delivery row lock of
     * the same transaction, never before one (see dispatch/service/AssignmentEngine). Must be the first read of the
     * partner in the transaction, so the entity is loaded under the lock.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from DeliveryPartner p where p.id = :id")
    Optional<DeliveryPartner> lockById(@Param("id") UUID id);

    /** Several partners locked in ascending id order (one statement), the same order in every transaction. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from DeliveryPartner p where p.id in :ids order by p.id")
    List<DeliveryPartner> lockAllById(@Param("ids") Collection<UUID> ids);

    /** The partner's lifecycle status only (a scalar: nothing is put into the persistence context). */
    @Query("select p.status from DeliveryPartner p where p.id = :id")
    Optional<PartnerStatus> findStatusById(@Param("id") UUID id);

    /**
     * Of {@code ids}, the partners available apart from "no other pending offer" (contract §2): APPROVED, online,
     * free, location stored at or after {@code freshSince}. A pre-filter only: the wave re-checks under the lock.
     */
    @Query("""
            select new com.grocery.delivery.partner.repository.CandidateRow(p.id, p.lastLatitude, p.lastLongitude)
            from DeliveryPartner p
            where p.id in :ids
              and p.status = com.grocery.delivery.partner.entity.PartnerStatus.APPROVED
              and p.online = true and p.activeDeliveryId is null
              and p.lastLatitude is not null and p.lastLongitude is not null
              and p.lastLocationAt >= :freshSince
            """)
    List<CandidateRow> findAvailableAmong(@Param("ids") Collection<UUID> ids, @Param("freshSince") Instant freshSince);

    /** APPROVED partners who are online (the admin's map), by name. Served by idx_delivery_partner_online. */
    @Query("""
            select p from DeliveryPartner p
            where p.online = true and p.status = com.grocery.delivery.partner.entity.PartnerStatus.APPROVED
            order by p.fullName, p.id
            """)
    List<DeliveryPartner> findOnline();
}
