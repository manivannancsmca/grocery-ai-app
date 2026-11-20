package com.grocery.delivery.dispatch.service;

import com.grocery.delivery.dispatch.dto.DispatchDtos.DeliveryView;
import com.grocery.delivery.dispatch.dto.DispatchDtos.OfferView;
import com.grocery.delivery.dispatch.entity.Delivery;
import com.grocery.delivery.dispatch.entity.DeliveryOffer;
import com.grocery.delivery.dispatch.entity.DeliveryStatus;
import com.grocery.delivery.dispatch.entity.OfferStatus;
import com.grocery.delivery.dispatch.event.DeliveryEventPublisher;
import com.grocery.delivery.dispatch.mapper.DeliveryMapper;
import com.grocery.delivery.dispatch.repository.DeliveryOfferRepository;
import com.grocery.delivery.dispatch.repository.DeliveryRepository;
import com.grocery.delivery.dispatch.repository.OfferRef;
import com.grocery.delivery.partner.entity.DeliveryPartner;
import com.grocery.delivery.partner.repository.DeliveryPartnerRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The partner's offers (contract §3): list, accept (the race: exactly one accept of a delivery wins), reject.
 * Both changes lock the delivery row first (see {@link AssignmentEngine} for the locking rule).
 */
@Service
public class OfferService {

    private static final Logger log = LoggerFactory.getLogger(OfferService.class);

    /** The result of an accept: the assigned delivery, or why the offer cannot be accepted (after commit). */
    private record AcceptOutcome(DeliveryView view, OfferStatus refusedAs) {
    }

    private final DeliveryRepository deliveries;
    private final DeliveryOfferRepository offers;
    private final DeliveryPartnerRepository partners;
    private final AssignmentEngine engine;
    private final DeliveryEventPublisher events;
    private final PartnerGuard guard;
    private final Clock clock;
    private final TransactionTemplate transaction;

    public OfferService(DeliveryRepository deliveries, DeliveryOfferRepository offers,
                        DeliveryPartnerRepository partners, AssignmentEngine engine, DeliveryEventPublisher events,
                        PartnerGuard guard, Clock clock, PlatformTransactionManager transactionManager) {
        this.deliveries = deliveries;
        this.offers = offers;
        this.partners = partners;
        this.engine = engine;
        this.events = events;
        this.guard = guard;
        this.clock = clock;
        this.transaction = new TransactionTemplate(transactionManager);
        this.transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    /** My open offers (PENDING, not expired), the one expiring first first. */
    @Transactional(readOnly = true)
    public List<OfferView> myOffers(UUID partnerId) {
        guard.requireApproved(partnerId);
        List<DeliveryOffer> open = offers.findOpenByPartner(partnerId, clock.instant());
        Map<UUID, Delivery> byId = deliveries.findAllById(open.stream().map(DeliveryOffer::getDeliveryId).toList())
                .stream().collect(Collectors.toMap(Delivery::getId, Function.identity()));
        return open.stream().filter(offer -> byId.containsKey(offer.getDeliveryId()))
                .map(offer -> DeliveryMapper.toOfferView(offer, byId.get(offer.getDeliveryId()))).toList();
    }

    /**
     * Accepts the offer: the delivery becomes ASSIGNED to the caller (+ DeliveryPartnerAssigned), every other open
     * offer of it is WITHDRAWN. Under the delivery row lock, so of concurrent accepts exactly one wins; the others then
     * find their offer WITHDRAWN → 409 OFFER_NOT_AVAILABLE ({@code currentStatus} = the offer's status). An offer found
     * expired is closed (EXPIRED) and the search moved on before the 409 is returned.
     */
    public DeliveryView accept(UUID partnerId, UUID offerId) {
        AcceptOutcome outcome = transaction.execute(status -> {
            guard.requireApproved(partnerId);
            OfferRef ref = ownOffer(partnerId, offerId);
            Delivery delivery = deliveries.lockById(ref.deliveryId()).orElseThrow(DispatchException::offerNotFound);
            DeliveryOffer offer = offers.findById(offerId).orElseThrow(DispatchException::offerNotFound);
            Instant now = clock.instant();
            if (offer.getStatus() != OfferStatus.PENDING) {
                return refused(offer.getStatus());
            }
            if (delivery.getStatus() != DeliveryStatus.SEARCHING) {
                // Cannot happen (leaving SEARCHING closes every offer); repaired rather than accepted
                offer.close(offer.isOpen(now) ? OfferStatus.WITHDRAWN : OfferStatus.EXPIRED, now);
                events.offerClosed(delivery, offer);
                return refused(offer.getStatus());
            }
            if (!offer.isOpen(now)) {
                engine.advance(delivery, now);                  // expires it (and the wave) and moves the search on
                return refused(OfferStatus.EXPIRED);
            }
            DeliveryPartner partner = partners.lockById(partnerId).orElseThrow(DispatchException::partnerNotActive);
            if (!partner.isApproved()) {
                throw DispatchException.partnerNotActive();
            }
            if (partner.getActiveDeliveryId() != null) {
                // Assigned elsewhere in the meantime (e.g. by an admin): this offer goes to someone else
                offer.close(OfferStatus.WITHDRAWN, now);
                events.offerClosed(delivery, offer);
                engine.advance(delivery, now);
                return refused(OfferStatus.WITHDRAWN);
            }
            offer.close(OfferStatus.ACCEPTED, now);
            engine.closePendingOffers(delivery, OfferStatus.WITHDRAWN, now);
            engine.assign(delivery, partner, false, partnerId, now);
            return new AcceptOutcome(DeliveryMapper.toView(delivery), null);
        });
        if (outcome.refusedAs() != null) {
            log.info("Partner {} could not accept offer {}: it is {}", partnerId, offerId, outcome.refusedAs());
            throw DispatchException.offerNotAvailable(outcome.refusedAs());
        }
        return outcome.view();
    }

    /**
     * Rejects the offer (REJECTED + DeliveryOfferClosed); when it was the last open offer of the wave the next wave
     * goes out at once. Idempotent: an offer that is no longer PENDING is left as it is.
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void reject(UUID partnerId, UUID offerId) {
        guard.requireApproved(partnerId);
        OfferRef ref = ownOffer(partnerId, offerId);
        Delivery delivery = deliveries.lockById(ref.deliveryId()).orElseThrow(DispatchException::offerNotFound);
        DeliveryOffer offer = offers.findById(offerId).orElseThrow(DispatchException::offerNotFound);
        if (offer.getStatus() != OfferStatus.PENDING) {
            return;
        }
        Instant now = clock.instant();
        offer.close(OfferStatus.REJECTED, now);
        events.offerClosed(delivery, offer);
        log.info("Partner {} rejected offer {} for order {}", partnerId, offerId, delivery.getOrderNumber());
        if (delivery.getStatus() == DeliveryStatus.SEARCHING) {
            engine.advance(delivery, now);
        }
    }

    /** The offer's delivery, if the offer is the caller's; unknown or another partner's → 404 OFFER_NOT_FOUND. */
    private OfferRef ownOffer(UUID partnerId, UUID offerId) {
        return offers.findRef(offerId).filter(ref -> ref.partnerId().equals(partnerId))
                .orElseThrow(DispatchException::offerNotFound);
    }

    private static AcceptOutcome refused(OfferStatus status) {
        return new AcceptOutcome(null, status);
    }
}
