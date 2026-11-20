package com.grocery.delivery.dispatch.service;

import com.grocery.delivery.dispatch.config.DispatchProperties;
import com.grocery.delivery.dispatch.dto.DispatchDtos.LocationRequest;
import com.grocery.delivery.dispatch.dto.DispatchDtos.PartnerStatusView;
import com.grocery.delivery.dispatch.entity.Delivery;
import com.grocery.delivery.dispatch.entity.DeliveryStatus;
import com.grocery.delivery.dispatch.event.DeliveryEvents.DeliveryLocationUpdated;
import com.grocery.delivery.dispatch.geo.PartnerGeoIndex;
import com.grocery.delivery.dispatch.location.LocationEventPublisher;
import com.grocery.delivery.dispatch.mapper.DeliveryMapper;
import com.grocery.delivery.dispatch.repository.DeliveryOfferRepository;
import com.grocery.delivery.dispatch.repository.DeliveryRepository;
import com.grocery.delivery.dispatch.repository.LocationTrail;
import com.grocery.delivery.partner.entity.DeliveryPartner;
import com.grocery.delivery.partner.repository.DeliveryPartnerRepository;
import com.grocery.delivery.stats.OnlineSessions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

/**
 * The partner's availability and live location (contract §3). Both lock only the partner's row. Redis and Kafka are
 * touched after the database transaction committed, best effort:
 * <ul>
 *   <li>online / offline: the {@code online} flag (offline refused with an active delivery → 409 ACTIVE_DELIVERY);
 *       GEOADD of the last known location when going online, ZREM when going offline;</li>
 *   <li>a location report (online only, else 409 PARTNER_OFFLINE): reports less than
 *       {@code location-min-interval} (2 s) after the last STORED one are accepted but neither stored nor published.
 *       A stored report becomes the partner's last location, is GEOADDed, and — while the partner has an ASSIGNED /
 *       PICKED_UP delivery — sampled into the trail (one row per 15 s) and published as DeliveryLocationUpdated on
 *       {@code delivery.location} (so at most one per partner per 2 s).</li>
 * </ul>
 */
@Service
public class AvailabilityService {

    private static final Logger log = LoggerFactory.getLogger(AvailabilityService.class);

    /** What a committed location report still has to do outside the transaction. */
    private record StoredReport(boolean stored, DeliveryLocationUpdated event) {
    }

    private final DeliveryPartnerRepository partners;
    private final DeliveryRepository deliveries;
    private final DeliveryOfferRepository offers;
    private final LocationTrail trail;
    private final PartnerGeoIndex geoIndex;
    private final LocationEventPublisher locationEvents;
    private final DispatchProperties properties;
    private final Clock clock;
    private final OnlineSessions onlineSessions;
    private final TransactionTemplate transaction;

    public AvailabilityService(DeliveryPartnerRepository partners, DeliveryRepository deliveries,
                               DeliveryOfferRepository offers, LocationTrail trail, PartnerGeoIndex geoIndex,
                               LocationEventPublisher locationEvents, DispatchProperties properties, Clock clock,
                               OnlineSessions onlineSessions, PlatformTransactionManager transactionManager) {
        this.partners = partners;
        this.deliveries = deliveries;
        this.offers = offers;
        this.trail = trail;
        this.geoIndex = geoIndex;
        this.locationEvents = locationEvents;
        this.properties = properties;
        this.clock = clock;
        this.onlineSessions = onlineSessions;
        this.transaction = new TransactionTemplate(transactionManager);
        this.transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    @Transactional(readOnly = true)
    public PartnerStatusView status(UUID partnerId) {
        DeliveryPartner partner = partners.findById(partnerId).filter(DeliveryPartner::isApproved)
                .orElseThrow(DispatchException::partnerNotActive);
        return DeliveryMapper.toStatusView(partner, offers.countOpenByPartner(partnerId, clock.instant()));
    }

    /** Goes online or offline; then adds to / removes from the Redis GEO set. */
    public PartnerStatusView setOnline(UUID partnerId, boolean online) {
        PartnerStatusView view = transaction.execute(status -> {
            DeliveryPartner partner = lockApproved(partnerId);
            if (!online && partner.getActiveDeliveryId() != null) {
                throw DispatchException.activeDelivery();
            }
            if (partner.isOnline() != online) {
                log.info("Partner {} is now {}", partnerId, online ? "online" : "offline");
                // The partner dashboard's online time: a session per stretch online.
                if (online) {
                    onlineSessions.wentOnline(partnerId, clock.instant());
                } else {
                    onlineSessions.wentOffline(partnerId, clock.instant());
                }
            }
            partner.setOnline(online);
            return DeliveryMapper.toStatusView(partner, offers.countOpenByPartner(partnerId, clock.instant()));
        });
        if (online) {
            if (view.location() != null) {
                geoIndex.put(partnerId, view.location().latitude(), view.location().longitude());
            }
        } else {
            geoIndex.remove(partnerId);
        }
        return view;
    }

    /** A location report (see the class comment). Never fails because of Redis or Kafka. */
    public void reportLocation(UUID partnerId, LocationRequest request) {
        double latitude = request.latitude();
        double longitude = request.longitude();
        StoredReport report = transaction.execute(status -> {
            DeliveryPartner partner = lockApproved(partnerId);
            if (!partner.isOnline()) {
                throw DispatchException.partnerOffline();
            }
            Instant now = clock.instant();
            Instant last = partner.getLastLocationAt();
            if (last != null && last.plus(properties.locationMinInterval()).isAfter(now)) {
                return new StoredReport(false, null);           // closer than 2 s to the last stored report
            }
            partner.recordLocation(latitude, longitude, request.heading(), request.speedKmh(),
                    request.accuracyMeters(), now);
            onlineSessions.seen(partnerId, now);                // still online (keeps the online session alive)
            DeliveryLocationUpdated event = null;
            if (partner.getActiveDeliveryId() != null) {
                Delivery delivery = deliveries.findById(partner.getActiveDeliveryId()).orElse(null);
                if (delivery != null && delivery.isAssignedTo(partnerId)
                        && DeliveryStatus.ACTIVE.contains(delivery.getStatus())) {
                    trail.sample(delivery.getId(), partnerId, latitude, longitude, request.heading(),
                            request.speedKmh(), request.accuracyMeters(), now, properties.trailSampleInterval());
                    event = new DeliveryLocationUpdated(delivery.getId(), delivery.getOrderId(), partnerId, latitude,
                            longitude, request.heading(), request.speedKmh(), request.accuracyMeters(), now);
                }
            }
            return new StoredReport(true, event);
        });
        if (report.stored()) {
            geoIndex.put(partnerId, latitude, longitude);
            if (report.event() != null) {
                locationEvents.publish(report.event());
            }
        }
    }

    /** The partner row, locked; not APPROVED (or no profile) → 403 PARTNER_NOT_ACTIVE. */
    private DeliveryPartner lockApproved(UUID partnerId) {
        return partners.lockById(partnerId).filter(DeliveryPartner::isApproved)
                .orElseThrow(DispatchException::partnerNotActive);
    }
}
