package com.grocery.delivery.dispatch.service;

import com.grocery.common.id.Ids;
import com.grocery.delivery.dispatch.config.DispatchProperties;
import com.grocery.delivery.dispatch.entity.Delivery;
import com.grocery.delivery.dispatch.entity.DeliveryOffer;
import com.grocery.delivery.dispatch.entity.DeliveryStatus;
import com.grocery.delivery.dispatch.entity.OfferStatus;
import com.grocery.delivery.dispatch.event.DeliveryEventPublisher;
import com.grocery.delivery.dispatch.geo.Distances;
import com.grocery.delivery.dispatch.geo.GeoPoint;
import com.grocery.delivery.dispatch.geo.PartnerGeoIndex;
import com.grocery.delivery.dispatch.geo.PartnerGeoIndex.GeoIndexUnavailableException;
import com.grocery.delivery.dispatch.repository.DeliveryOfferRepository;
import com.grocery.delivery.dispatch.repository.DeliveryRepository;
import com.grocery.delivery.partner.entity.DeliveryPartner;
import com.grocery.delivery.partner.repository.CandidateRow;
import com.grocery.delivery.partner.repository.DeliveryPartnerRepository;
import com.grocery.delivery.settings.DeliverySettings;
import com.grocery.delivery.settings.DeliverySettingsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The assignment rules of contract §2: waves of offers to the nearest available partners, expiry, the next wave, the
 * radius steps, {@code assignmentFailed} and the retry — plus the pieces every flow shares (closing offers, assigning).
 *
 * <h2>Locking rule (all of dispatch)</h2>
 * <ol>
 *   <li>A change of a delivery or of any of its offers first takes the delivery row lock ({@code SELECT … FOR UPDATE};
 *       the poller claims with {@code FOR UPDATE SKIP LOCKED}). Offers are changed only under their delivery's lock, so
 *       an accept, a rejection, the expiry and a withdrawal of one delivery's offers never overlap: of two partners
 *       accepting at the same moment exactly one wins, the other then sees its offer WITHDRAWN.</li>
 *   <li>Partner rows are locked after the delivery row(s), several at once in ascending id order. Nothing locks a
 *       partner first and a delivery afterwards (location reports and availability lock only the partner), so the
 *       lock order is acyclic.</li>
 *   <li>A wave locks its candidate partners before offering and re-checks them under the lock (approved, online,
 *       free, fresh location, no other open offer), so two deliveries searching at the same moment never both offer
 *       the same partner, and a partner who just accepted elsewhere is never offered.</li>
 * </ol>
 * Transactions run READ COMMITTED: a read after a lock sees what the previous holder committed, and the claim's
 * {@code SKIP LOCKED} locks only the rows it returns. Entities read before their lock would be stale in the persistence
 * context, so every flow reads a delivery or partner for the first time through its locking query (offers and
 * partner checks before that use scalar queries).
 *
 * <p>Every method here expects the caller's transaction and the delivery's lock.
 */
@Component
public class AssignmentEngine {

    private static final Logger log = LoggerFactory.getLogger(AssignmentEngine.class);

    /** A partner a wave can offer to, with their straight-line distance to the search point. */
    record Candidate(UUID partnerId, double distanceKm) {
    }

    private final DeliveryRepository deliveries;
    private final DeliveryOfferRepository offers;
    private final DeliveryPartnerRepository partners;
    private final PartnerGeoIndex geoIndex;
    private final DeliveryEventPublisher events;
    private final DispatchProperties properties;
    private final DeliverySettingsService settings;
    private final TransactionTemplate transaction;

    public AssignmentEngine(DeliveryRepository deliveries, DeliveryOfferRepository offers,
                            DeliveryPartnerRepository partners, PartnerGeoIndex geoIndex,
                            DeliveryEventPublisher events, DispatchProperties properties,
                            DeliverySettingsService settings, PlatformTransactionManager transactionManager) {
        this.deliveries = deliveries;
        this.offers = offers;
        this.partners = partners;
        this.geoIndex = geoIndex;
        this.events = events;
        this.properties = properties;
        this.settings = settings;
        this.transaction = new TransactionTemplate(transactionManager);
        this.transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    // ---- the poller's batch -------------------------------------------------------------------------------------

    /**
     * One batch of the dispatch poller, in its own READ COMMITTED transaction: claims up to {@code limit} SEARCHING
     * deliveries due at {@code now} ({@code FOR UPDATE SKIP LOCKED}) and {@link #advance advances} each. If the batch
     * fails, the same deliveries are retried one per transaction, and one that fails on its own is postponed by a poll
     * interval, so a single bad delivery never blocks the others. Returns how many were claimed.
     */
    public int advanceDue(Instant now, int limit) {
        if (limit <= 0) {
            throw new IllegalArgumentException("limit must be positive");
        }
        List<UUID> claimed = new ArrayList<>();
        try {
            transaction.executeWithoutResult(status -> {
                List<UUID> ids = deliveries.claimDue(now, limit).stream().map(UUID::fromString).toList();
                claimed.addAll(ids);
                for (Delivery delivery : deliveries.findAllById(ids)) {
                    advanceIfDue(delivery, now);
                }
            });
            return claimed.size();
        } catch (RuntimeException batchFailure) {
            log.warn("Dispatch batch of {} deliveries failed, retrying them one by one: {}", claimed.size(),
                    batchFailure.toString());
        }
        for (UUID id : claimed) {
            try {
                transaction.executeWithoutResult(status -> deliveries.claimOne(id.toString(), now).ifPresent(
                        claimedId -> deliveries.findById(id).ifPresent(delivery -> advanceIfDue(delivery, now))));
            } catch (RuntimeException e) {
                log.error("Dispatch of delivery {} failed, postponed by {}: {}", id, properties.pollInterval(),
                        e.toString(), e);
                try {
                    transaction.executeWithoutResult(
                            status -> deliveries.postpone(id.toString(), now.plus(properties.pollInterval())));
                } catch (RuntimeException postponeFailure) {
                    log.warn("Could not postpone delivery {}: {}", id, postponeFailure.toString());
                }
            }
        }
        return claimed.size();
    }

    private void advanceIfDue(Delivery delivery, Instant now) {
        // Locked by the claim; re-checked anyway so a delivery is never advanced early or after it left SEARCHING
        if (delivery.getStatus() == DeliveryStatus.SEARCHING && delivery.getNextSearchAt() != null
                && !delivery.getNextSearchAt().isAfter(now)) {
            advance(delivery, now);
        }
    }

    // ---- search -------------------------------------------------------------------------------------------------

    /**
     * Moves a SEARCHING delivery on: expires its overdue offers (EXPIRED + DeliveryOfferClosed); while offers are
     * still open, waits for them; otherwise sends the next wave — or, when the round is exhausted, waits for the retry
     * time and then starts a fresh round from the smallest radius (partners offered before included).
     */
    public void advance(Delivery delivery, Instant now) {
        requireSearching(delivery);
        // The settings in force now (an admin may have changed them since the last wave): read once per step
        DeliverySettings current = settings.current();
        Instant firstExpiry = null;
        for (DeliveryOffer offer : offers.findByDeliveryIdAndStatus(delivery.getId(), OfferStatus.PENDING)) {
            if (offer.isOpen(now)) {
                firstExpiry = firstExpiry == null || offer.getExpiresAt().isBefore(firstExpiry)
                        ? offer.getExpiresAt() : firstExpiry;
            } else {
                offer.close(OfferStatus.EXPIRED, now);
                events.offerClosed(delivery, offer);
            }
        }
        if (firstExpiry != null) {
            delivery.waitForOffers(firstExpiry);
            return;
        }
        if (delivery.isRoundExhausted(current.searchRadiiKm().size())) {
            if (delivery.getNextSearchAt() != null && delivery.getNextSearchAt().isAfter(now)) {
                return;                                         // assignment failed: retry not due yet
            }
            delivery.startNewRound(now);
            log.info("Delivery {} (order {}): search round {} after the retry interval", delivery.getId(),
                    delivery.getOrderNumber(), delivery.getSearchRound());
        }
        runWave(delivery, now, current);
    }

    /** {@link #runWave(Delivery, Instant, DeliverySettings)} with the settings in force now. */
    void runWave(Delivery delivery, Instant now) {
        runWave(delivery, now, settings.current());
    }

    /**
     * One wave (contract §2): from the current radius outwards, the first radius with available partners not yet
     * offered this delivery in this round gets the {@code offer-batch-size} nearest of them, each an offer valid for
     * {@code offer-ttl}. Nobody inside the largest radius → {@code assignmentFailed} (+ DeliveryAssignmentFailed the
     * first time) and a fresh round after {@code retry-interval}. Redis unreachable → the same wave after a poll
     * interval (never declared failed for that). Radii, batch size, offer time and freshness are the admin platform
     * settings in force ({@link DeliverySettings}); a shorter radius list continues from its largest step.
     */
    private void runWave(Delivery delivery, Instant now, DeliverySettings current) {
        requireSearching(delivery);
        List<Double> radii = current.searchRadiiKm();
        GeoPoint point = delivery.searchPoint();
        if (point != null) {
            Set<UUID> excluded = new HashSet<>(delivery.getExcludedPartners());
            excluded.addAll(offers.partnersOfferedInRound(delivery.getId(), delivery.getSearchRound()));
            for (int index = Math.min(delivery.getSearchRadiusIndex(), radii.size() - 1); index < radii.size(); index++) {
                List<Candidate> picked;
                try {
                    picked = pickCandidates(point, radii.get(index), excluded, now, current);
                } catch (GeoIndexUnavailableException e) {
                    log.warn("Delivery {} (order {}): partner search unavailable, retried in {}: {}",
                            delivery.getId(), delivery.getOrderNumber(), properties.pollInterval(), e.getMessage());
                    delivery.retrySearchAt(now.plus(properties.pollInterval()));
                    return;
                }
                if (!picked.isEmpty()) {
                    sendOffers(delivery, picked, index, now, current);
                    return;
                }
            }
        } else {
            log.warn("Delivery {} (order {}) has neither a store nor a drop location: nobody can be searched",
                    delivery.getId(), delivery.getOrderNumber());
        }
        boolean first = delivery.searchFailed(radii.size(), now.plus(properties.retryInterval()), now);
        if (first) {
            events.assignmentFailed(delivery, current.largestRadiusKm(), now);
        }
        log.warn("Delivery {} (order {}): no available partner within {} km{}; retried at {}", delivery.getId(),
                delivery.getOrderNumber(), current.largestRadiusKm(),
                first ? " (DeliveryAssignmentFailed)" : "", delivery.getNextSearchAt());
    }

    /**
     * The up to {@code offer-batch-size} nearest available partners within {@code radiusKm} of {@code point}, nearest
     * first: Redis GEOSEARCH, then a pre-filter in the database (no lock), then the chosen ones locked and re-checked.
     */
    List<Candidate> pickCandidates(GeoPoint point, double radiusKm, Set<UUID> excluded, Instant now,
                                   DeliverySettings current) {
        List<UUID> nearby = new ArrayList<>(geoIndex.nearby(point, radiusKm));
        nearby.removeIf(excluded::contains);
        if (nearby.isEmpty()) {
            return List.of();
        }
        Instant freshSince = now.minus(current.locationFreshness());
        List<CandidateRow> rows = partners.findAvailableAmong(nearby, freshSince);
        if (rows.isEmpty()) {
            return List.of();
        }
        Set<UUID> withOffers = new HashSet<>(offers.partnersWithOpenOffers(
                rows.stream().map(CandidateRow::id).toList(), now));
        List<Candidate> ranked = rows.stream()
                .filter(row -> !withOffers.contains(row.id()))
                .map(row -> new Candidate(row.id(),
                        Distances.haversineKm(new GeoPoint(row.latitude(), row.longitude()), point)))
                .filter(candidate -> candidate.distanceKm() <= radiusKm)
                .sorted(NEAREST_FIRST)
                .toList();

        int wanted = current.offersPerWave();
        List<Candidate> picked = new ArrayList<>();
        int next = 0;
        while (picked.size() < wanted && next < ranked.size()) {
            List<Candidate> chunk = ranked.subList(next, Math.min(ranked.size(), next + wanted - picked.size()));
            next += chunk.size();
            List<UUID> ids = chunk.stream().map(Candidate::partnerId).toList();
            // First read of these partners in this transaction: loaded under the lock, so current
            Map<UUID, DeliveryPartner> locked = partners.lockAllById(ids).stream()
                    .collect(Collectors.toMap(DeliveryPartner::getId, Function.identity()));
            Set<UUID> stillOffered = new HashSet<>(offers.partnersWithOpenOffers(ids, now));
            for (Candidate candidate : chunk) {
                DeliveryPartner partner = locked.get(candidate.partnerId());
                if (partner == null || !partner.isAvailable(freshSince) || stillOffered.contains(partner.getId())) {
                    continue;
                }
                double km = Distances.haversineKm(
                        new GeoPoint(partner.getLastLatitude(), partner.getLastLongitude()), point);
                if (km <= radiusKm) {
                    picked.add(new Candidate(partner.getId(), km));
                }
            }
        }
        picked.sort(NEAREST_FIRST);
        return picked;
    }

    private static final Comparator<Candidate> NEAREST_FIRST =
            Comparator.comparingDouble(Candidate::distanceKm).thenComparing(Candidate::partnerId);

    private void sendOffers(Delivery delivery, List<Candidate> picked, int radiusIndex, Instant now,
                            DeliverySettings current) {
        Instant expiresAt = now.plus(current.offerTimeLimit());
        int wave = delivery.getSearchWave() + 1;
        for (Candidate candidate : picked) {
            DeliveryOffer offer = offers.save(new DeliveryOffer(Ids.newId(), delivery.getId(), candidate.partnerId(),
                    delivery.getSearchRound(), wave, candidate.distanceKm(), now, expiresAt));
            events.offerCreated(delivery, offer);
        }
        delivery.waveSent(radiusIndex, picked.size(), expiresAt, now);
        log.info("Delivery {} (order {}): wave {} of round {} offered to {} partner(s) within {} km, expiring {}",
                delivery.getId(), delivery.getOrderNumber(), wave, delivery.getSearchRound(), picked.size(),
                current.searchRadiiKm().get(radiusIndex), expiresAt);
    }

    // ---- shared steps -------------------------------------------------------------------------------------------

    /**
     * Closes every PENDING offer of the delivery with {@code outcome} (WITHDRAWN: another partner accepted, the
     * delivery was cancelled or reassigned) + DeliveryOfferClosed each; an offer already past its expiry is closed as
     * EXPIRED instead.
     */
    public void closePendingOffers(Delivery delivery, OfferStatus outcome, Instant now) {
        for (DeliveryOffer offer : offers.findByDeliveryIdAndStatus(delivery.getId(), OfferStatus.PENDING)) {
            offer.close(offer.isOpen(now) ? outcome : OfferStatus.EXPIRED, now);
            events.offerClosed(delivery, offer);
        }
    }

    /**
     * Assigns {@code partner} (locked, checked approved and free by the caller): delivery ASSIGNED, the partner's
     * active delivery, DeliveryPartnerAssigned.
     */
    public void assign(Delivery delivery, DeliveryPartner partner, boolean manual, UUID actorUserId, Instant now) {
        delivery.assign(partner.getId(), manual, actorUserId, now);
        partner.startDelivery(delivery.getId());
        events.partnerAssigned(delivery, partner, manual);
        log.info("Delivery {} (order {}) assigned to partner {}{}", delivery.getId(), delivery.getOrderNumber(),
                partner.getId(), manual ? " by an admin" : "");
    }

    /** Frees the delivery's partner (delivered, failed, cancelled): locks their row after the delivery's. */
    public void releasePartner(Delivery delivery, UUID partnerId) {
        if (partnerId != null) {
            partners.lockById(partnerId).ifPresent(partner -> partner.finishDelivery(delivery.getId()));
        }
    }

    private static void requireSearching(Delivery delivery) {
        if (delivery.getStatus() != DeliveryStatus.SEARCHING) {
            throw new IllegalStateException("Delivery " + delivery.getId() + " is " + delivery.getStatus());
        }
    }
}
