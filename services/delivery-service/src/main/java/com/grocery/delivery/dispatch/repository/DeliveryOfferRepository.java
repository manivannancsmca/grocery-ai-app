package com.grocery.delivery.dispatch.repository;

import com.grocery.delivery.dispatch.entity.DeliveryOffer;
import com.grocery.delivery.dispatch.entity.OfferStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Offers change only under their delivery's row lock; these reads do not lock. */
public interface DeliveryOfferRepository extends JpaRepository<DeliveryOffer, UUID> {

    /** The offer's delivery and partner (immutable), without loading the entity. */
    @Query("select new com.grocery.delivery.dispatch.repository.OfferRef(o.deliveryId, o.partnerId) "
            + "from DeliveryOffer o where o.id = :id")
    Optional<OfferRef> findRef(@Param("id") UUID id);

    List<DeliveryOffer> findByDeliveryIdAndStatus(UUID deliveryId, OfferStatus status);

    /** Every offer of a delivery, oldest first (admin detail). */
    List<DeliveryOffer> findByDeliveryIdOrderBySentAtAscIdAsc(UUID deliveryId);

    /** Partners offered this delivery in one search round (not offered it again in that round). */
    @Query("select o.partnerId from DeliveryOffer o where o.deliveryId = :deliveryId and o.searchRound = :round")
    List<UUID> partnersOfferedInRound(@Param("deliveryId") UUID deliveryId, @Param("round") int round);

    /** Of {@code partnerIds}, those holding an open offer (PENDING, not expired at {@code now}). */
    @Query("""
            select distinct o.partnerId from DeliveryOffer o
            where o.partnerId in :partnerIds
              and o.status = com.grocery.delivery.dispatch.entity.OfferStatus.PENDING and o.expiresAt > :now
            """)
    List<UUID> partnersWithOpenOffers(@Param("partnerIds") Collection<UUID> partnerIds, @Param("now") Instant now);

    /** The partner's open offers (PENDING, not expired), the one expiring first first. */
    @Query("""
            select o from DeliveryOffer o
            where o.partnerId = :partnerId
              and o.status = com.grocery.delivery.dispatch.entity.OfferStatus.PENDING and o.expiresAt > :now
            order by o.expiresAt, o.id
            """)
    List<DeliveryOffer> findOpenByPartner(@Param("partnerId") UUID partnerId, @Param("now") Instant now);

    @Query("""
            select count(o) from DeliveryOffer o
            where o.partnerId = :partnerId
              and o.status = com.grocery.delivery.dispatch.entity.OfferStatus.PENDING and o.expiresAt > :now
            """)
    long countOpenByPartner(@Param("partnerId") UUID partnerId, @Param("now") Instant now);

    /** Every offer sent to the partner within [start, end), whatever became of it: their dashboard statistics. */
    @Query("select o from DeliveryOffer o where o.partnerId = :partnerId and o.sentAt >= :start and o.sentAt < :end")
    List<DeliveryOffer> findSentToPartner(@Param("partnerId") UUID partnerId, @Param("start") Instant start,
                                          @Param("end") Instant end);

    /** The deliveries on which the partner holds a PENDING offer (also already expired ones), as a scalar list. */
    @Query("""
            select distinct o.deliveryId from DeliveryOffer o
            where o.partnerId = :partnerId and o.status = com.grocery.delivery.dispatch.entity.OfferStatus.PENDING
            """)
    List<UUID> deliveriesWithPendingOffersOf(@Param("partnerId") UUID partnerId);
}
