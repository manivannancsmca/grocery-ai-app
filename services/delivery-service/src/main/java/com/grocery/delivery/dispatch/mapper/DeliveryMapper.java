package com.grocery.delivery.dispatch.mapper;

import com.grocery.delivery.dispatch.dto.DispatchDtos.AdminDeliveryView;
import com.grocery.delivery.dispatch.dto.DispatchDtos.AdminOfferView;
import com.grocery.delivery.dispatch.dto.DispatchDtos.DeliverySummary;
import com.grocery.delivery.dispatch.dto.DispatchDtos.DeliveryView;
import com.grocery.delivery.dispatch.dto.DispatchDtos.DropView;
import com.grocery.delivery.dispatch.dto.DispatchDtos.FailureView;
import com.grocery.delivery.dispatch.dto.DispatchDtos.LocationView;
import com.grocery.delivery.dispatch.dto.DispatchDtos.OfferView;
import com.grocery.delivery.dispatch.dto.DispatchDtos.OnlinePartnerView;
import com.grocery.delivery.dispatch.dto.DispatchDtos.PartnerLocationView;
import com.grocery.delivery.dispatch.dto.DispatchDtos.PartnerStatusView;
import com.grocery.delivery.dispatch.dto.DispatchDtos.StoreDeliveryView;
import com.grocery.delivery.dispatch.dto.DispatchDtos.StorePartnerView;
import com.grocery.delivery.dispatch.dto.DispatchDtos.StoreView;
import com.grocery.delivery.dispatch.dto.DispatchDtos.TimelineEntry;
import com.grocery.delivery.dispatch.dto.DispatchDtos.TrackingDrop;
import com.grocery.delivery.dispatch.dto.DispatchDtos.TrackingPartner;
import com.grocery.delivery.dispatch.dto.DispatchDtos.TrackingPickup;
import com.grocery.delivery.dispatch.dto.DispatchDtos.TrackingView;
import com.grocery.delivery.dispatch.entity.Delivery;
import com.grocery.delivery.dispatch.entity.DeliveryOffer;
import com.grocery.delivery.dispatch.entity.DeliveryStatus;
import com.grocery.delivery.dispatch.geo.Distances;
import com.grocery.delivery.dispatch.geo.GeoPoint;
import com.grocery.delivery.partner.entity.DeliveryPartner;

import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** Plain Java mapping of deliveries, offers and partners to the API views. Distances rounded to 0.1 km. */
public final class DeliveryMapper {

    private DeliveryMapper() {
    }

    public static DeliveryView toView(Delivery d) {
        return new DeliveryView(d.getId(), d.getOrderId(), d.getOrderNumber(), d.getOrderType(), d.getStatus().name(),
                d.isReadyForPickup(), d.getReadyBy(), pickup(d), drop(d), d.getNote(), d.getItemCount(), d.getTotal(),
                d.getCurrency(), d.getAssignedAt(), d.getPickedUpAt(), d.getDeliveredAt(), d.getFailedAt(),
                failure(d), timeline(d));
    }

    public static AdminDeliveryView toAdminView(Delivery d, String partnerName, List<AdminOfferView> offers) {
        return new AdminDeliveryView(d.getId(), d.getOrderId(), d.getOrderNumber(), d.getOrderType(),
                d.getStatus().name(), d.isReadyForPickup(), d.getReadyBy(), pickup(d), drop(d), d.getNote(),
                d.getItemCount(), d.getTotal(), d.getCurrency(), d.getAssignedAt(), d.getPickedUpAt(),
                d.getDeliveredAt(), d.getFailedAt(), failure(d), timeline(d), d.getPartnerId(), partnerName,
                d.isAssignmentFailed(), d.getOffersSent(), offers);
    }

    public static AdminOfferView toAdminOfferView(DeliveryOffer o, String partnerName) {
        return new AdminOfferView(o.getId(), o.getPartnerId(), partnerName, Distances.roundKm(o.getDistanceKm()),
                o.getStatus().name(), o.getSentAt(), o.getClosedAt());
    }

    /** What a partner sees of an offer: the store, the drop AREA and the trip length — no customer details. */
    public static OfferView toOfferView(DeliveryOffer o, Delivery d) {
        return new OfferView(o.getId(), d.getId(), d.getOrderNumber(), d.getOrderType(), pickup(d), dropArea(d),
                Distances.roundKm(o.getDistanceKm()), tripKm(d), d.getItemCount(), o.getExpiresAt());
    }

    public static DeliverySummary toSummary(Delivery d) {
        return new DeliverySummary(d.getId(), d.getOrderNumber(), d.getStatus().name(), d.getStoreName(),
                dropArea(d), tripKm(d), d.getPickedUpAt(), d.getFinishedAt());
    }

    public static PartnerStatusView toStatusView(DeliveryPartner p, long pendingOffers) {
        LocationView location = p.hasLocation()
                ? new LocationView(p.getLastLatitude(), p.getLastLongitude(), p.getLastLocationAt()) : null;
        return new PartnerStatusView(p.getId(), p.isOnline(), location, p.getActiveDeliveryId(), pendingOffers);
    }

    public static OnlinePartnerView toOnlineView(DeliveryPartner p) {
        return new OnlinePartnerView(p.getId(), p.getFullName(),
                p.getVehicleType() == null ? null : p.getVehicleType().name(), p.getLastLatitude(),
                p.getLastLongitude(), p.getLastLocationAt(), p.getActiveDeliveryId() != null, p.getActiveDeliveryId());
    }

    /**
     * The customer's view: the partner's first name and vehicle, the pickup and drop points; the partner's phone and
     * number plate (to call them, to spot them), position and the ETA only while the partner is on the way
     * (ASSIGNED / PICKED_UP). Once the delivery is over the customer no longer gets the partner's number (privacy).
     */
    public static TrackingView toTracking(Delivery d, DeliveryPartner partner) {
        boolean active = DeliveryStatus.ACTIVE.contains(d.getStatus());
        TrackingPartner partnerView = partner == null ? null : new TrackingPartner(firstName(partner.getFullName()),
                partner.getVehicleType() == null ? null : partner.getVehicleType().name(),
                active ? partner.getPhone() : null, active ? partner.getVehicleNumber() : null);
        PartnerLocationView location = null;
        Integer eta = null;
        if (active && partner != null && partner.hasLocation()) {
            location = new PartnerLocationView(partner.getLastLatitude(), partner.getLastLongitude(),
                    partner.getLastHeading(), partner.getLastLocationAt());
            eta = etaMinutes(d, new GeoPoint(partner.getLastLatitude(), partner.getLastLongitude()));
        }
        return new TrackingView(d.getOrderId(), d.getStatus().name(), partnerView,
                new TrackingPickup(d.getStoreName(), d.getStoreLatitude(), d.getStoreLongitude()),
                new TrackingDrop(d.getDropLatitude(), d.getDropLongitude()), location, eta, timeline(d));
    }

    /**
     * The store's view of a delivery still at the store: the partner collecting it (full name, phone and vehicle: the
     * store hands the order over to them) and, only while ASSIGNED with a known partner position and pickup point,
     * where the partner is and how far from the store (straight line, 0.1 km; ETA × 1.3 at 20 km/h, rounded up).
     */
    public static StoreDeliveryView toStoreView(Delivery d, DeliveryPartner partner) {
        StorePartnerView partnerView = partner == null ? null : new StorePartnerView(partner.getFullName(),
                partner.getPhone(), partner.getVehicleType() == null ? null : partner.getVehicleType().name(),
                partner.getVehicleNumber());
        GeoPoint store = d.pickupPoint();
        PartnerLocationView location = null;
        Double distanceKm = null;
        Integer eta = null;
        if (d.getStatus() == DeliveryStatus.ASSIGNED && partner != null && partner.hasLocation() && store != null) {
            location = new PartnerLocationView(partner.getLastLatitude(), partner.getLastLongitude(),
                    partner.getLastHeading(), partner.getLastLocationAt());
            double km = Distances.haversineKm(new GeoPoint(partner.getLastLatitude(), partner.getLastLongitude()),
                    store);
            distanceKm = Distances.roundKm(km);
            eta = Distances.etaMinutes(km);
        }
        return new StoreDeliveryView(d.getId(), d.getOrderId(), d.getOrderNumber(), d.getStatus().name(),
                d.isAssignmentFailed(), d.getAssignedAt(), partnerView, location, distanceKm, eta);
    }

    /** Partner → (store →) customer, straight line × 1.3 at 20 km/h, rounded up; null without a drop point. */
    static Integer etaMinutes(Delivery d, GeoPoint partnerAt) {
        GeoPoint drop = d.dropPoint();
        if (drop == null) {
            return null;
        }
        GeoPoint pickup = d.pickupPoint();
        double km = d.getStatus() == DeliveryStatus.ASSIGNED && pickup != null
                ? Distances.haversineKm(partnerAt, pickup) + Distances.haversineKm(pickup, drop)
                : Distances.haversineKm(partnerAt, drop);
        return Distances.etaMinutes(km);
    }

    /** Store → customer, km (0.1), or null when either point is unknown. */
    public static Double tripKm(Delivery d) {
        return Distances.roundKm(Distances.haversineKmOrNull(d.pickupPoint(), d.dropPoint()));
    }

    /**
     * The area of the drop address, like the store board: the landmark (else line 2) and the city, e.g.
     * "Indiranagar, Bengaluru"; just the city when neither is set; null without an address.
     */
    public static String dropArea(Delivery d) {
        String locality = clean(d.getDropLandmark()) != null ? clean(d.getDropLandmark()) : clean(d.getDropLine2());
        String area = Stream.of(locality, clean(d.getDropCity())).filter(Objects::nonNull)
                .collect(Collectors.joining(", "));
        return area.isEmpty() ? null : area;
    }

    /** "Ravi Kumar" → "Ravi" (the customer sees the partner's first name only). */
    public static String firstName(String fullName) {
        String name = clean(fullName);
        return name == null ? null : name.split("\\s+")[0];
    }

    private static StoreView pickup(Delivery d) {
        return new StoreView(d.getStoreName(), d.getStoreCity(), d.getStoreLatitude(), d.getStoreLongitude());
    }

    private static DropView drop(Delivery d) {
        return new DropView(d.getDropRecipientName(), d.getDropPhone(), d.getDropLine1(), d.getDropLine2(),
                d.getDropLandmark(), d.getDropCity(), d.getDropPostalCode(), d.getDropLatitude(), d.getDropLongitude());
    }

    private static FailureView failure(Delivery d) {
        return d.getFailureReason() == null ? null : new FailureView(d.getFailureReason().name(), d.getFailureNote());
    }

    private static List<TimelineEntry> timeline(Delivery d) {
        return d.getHistory().stream().map(change -> new TimelineEntry(change.getStatus().name(),
                change.getChangedAt())).toList();
    }

    private static String clean(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
