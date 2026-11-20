package com.grocery.delivery.dispatch.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Request and response bodies of docs/api/phase-9-delivery.md §3–§5 and its store addendum. */
public final class DispatchDtos {

    public static final String FAILURE_REASON = "^(CUSTOMER_UNREACHABLE|ADDRESS_NOT_FOUND|CUSTOMER_REFUSED|OTHER)$";

    private DispatchDtos() {
    }

    // ---- requests ---------------------------------------------------------------------------------------------------

    public record AvailabilityRequest(@NotNull Boolean online) {
    }

    public record LocationRequest(
            @NotNull @DecimalMin("-90") @DecimalMax("90") Double latitude,
            @NotNull @DecimalMin("-180") @DecimalMax("180") Double longitude,
            @DecimalMin("0") Double accuracyMeters,
            @DecimalMin("0") @DecimalMax("360") Double heading,
            @DecimalMin("0") Double speedKmh) {
    }

    /** {@code note} is required when the reason is OTHER (checked by the service). */
    public record FailRequest(
            @NotNull @Pattern(regexp = FAILURE_REASON,
                    message = "must be one of CUSTOMER_UNREACHABLE, ADDRESS_NOT_FOUND, CUSTOMER_REFUSED, OTHER")
            String reason,
            @Size(max = 300) String note) {
    }

    /** Both optional: with {@code partnerId} assign that partner now; without, search again. */
    public record ReassignRequest(UUID partnerId, @Size(max = 300) String reason) {
    }

    // ---- partner ------------------------------------------------------------------------------------------------

    public record LocationView(double latitude, double longitude, Instant at) {
    }

    public record PartnerStatusView(UUID partnerId, boolean online, LocationView location, UUID activeDeliveryId,
                                    long pendingOffers) {
    }

    /** The store as a partner sees it before and after accepting (pickup point). */
    public record StoreView(String name, String city, Double latitude, Double longitude) {
    }

    /** Before acceptance the partner sees no customer details: only the area and the trip length. */
    public record OfferView(UUID offerId, UUID deliveryId, String orderNumber, String orderType, StoreView store,
                            String dropArea, double distanceKm, Double tripKm, int itemCount, Instant expiresAt) {
    }

    public record DropView(String recipientName, String phone, String line1, String line2, String landmark,
                           String city, String postalCode, Double latitude, Double longitude) {
    }

    public record FailureView(String reason, String note) {
    }

    public record TimelineEntry(String status, Instant at) {
    }

    /** The assigned partner's (and admins') view: includes the drop details. */
    public record DeliveryView(UUID deliveryId, UUID orderId, String orderNumber, String orderType, String status,
                               boolean readyForPickup, Instant readyBy, StoreView pickup, DropView drop, String note,
                               int itemCount, BigDecimal total, String currency, Instant assignedAt,
                               Instant pickedUpAt, Instant deliveredAt, Instant failedAt, FailureView failure,
                               List<TimelineEntry> timeline) {
    }

    public record DeliverySummary(UUID deliveryId, String orderNumber, String status, String store, String dropArea,
                                  Double tripKm, Instant pickedUpAt, Instant finishedAt) {
    }

    public record HistorySummary(long deliveredToday, long deliveredThisWeek, long failedThisWeek) {
    }

    // ---- customer tracking --------------------------------------------------------------------------------------

    /**
     * The partner as the customer sees them: the first name and vehicle; {@code phone} and {@code vehicleNumber} only
     * while the partner holds the delivery (ASSIGNED / PICKED_UP), so the customer can call them and spot them, but
     * does not keep their number afterwards (null then).
     */
    public record TrackingPartner(String name, String vehicleType, String phone, String vehicleNumber) {
    }

    public record TrackingPickup(String name, Double latitude, Double longitude) {
    }

    public record TrackingDrop(Double latitude, Double longitude) {
    }

    public record PartnerLocationView(double latitude, double longitude, Double heading, Instant at) {
    }

    public record TrackingView(UUID orderId, String status, TrackingPartner partner, TrackingPickup pickup,
                               TrackingDrop drop, PartnerLocationView partnerLocation, Integer etaMinutes,
                               List<TimelineEntry> timeline) {
    }

    // ---- store (vendor / restaurant owner) ----------------------------------------------------------------------

    /** The partner collecting the order, as the store sees them: full name and phone (the store hands it over). */
    public record StorePartnerView(String name, String phone, String vehicleType, String vehicleNumber) {
    }

    /**
     * A delivery still at the store (PENDING / SEARCHING / ASSIGNED). The partner's position, distance and ETA to the
     * store only while ASSIGNED, the partner has a location and the delivery a pickup point.
     */
    public record StoreDeliveryView(UUID deliveryId, UUID orderId, String orderNumber, String status,
                                    boolean assignmentFailed, Instant assignedAt, StorePartnerView partner,
                                    PartnerLocationView partnerLocation, Double distanceToStoreKm,
                                    Integer etaToStoreMinutes) {
    }

    // ---- admin --------------------------------------------------------------------------------------------------

    public record AdminOfferView(UUID offerId, UUID partnerId, String partnerName, double distanceKm, String status,
                                 Instant sentAt, Instant closedAt) {
    }

    /**
     * DeliveryView + the partner, {@code assignmentFailed} and the number of offers sent; {@code offers} only in the
     * detail (and the reassign answer), left out of list items.
     */
    public record AdminDeliveryView(UUID deliveryId, UUID orderId, String orderNumber, String orderType,
                                    String status, boolean readyForPickup, Instant readyBy, StoreView pickup,
                                    DropView drop, String note, int itemCount, BigDecimal total, String currency,
                                    Instant assignedAt, Instant pickedUpAt, Instant deliveredAt, Instant failedAt,
                                    FailureView failure, List<TimelineEntry> timeline, UUID partnerId,
                                    String partnerName, boolean assignmentFailed, int offersSent,
                                    @JsonInclude(JsonInclude.Include.NON_NULL) List<AdminOfferView> offers) {
    }

    public record OnlinePartnerView(UUID partnerId, String name, String vehicleType, Double latitude,
                                    Double longitude, Instant locationAt, boolean busy, UUID activeDeliveryId) {
    }
}
