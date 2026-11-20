package com.grocery.delivery.partner.entity;

import com.grocery.common.web.BusinessRuleException;
import com.grocery.common.web.ConflictException;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * A delivery partner (rider) and their approval lifecycle. The id IS the identity user id. All status
 * rules live here; services only load, call a method, save, record history and publish the event.
 */
@Entity
@Table(name = "delivery_partner")
public class DeliveryPartner {

    @Id
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PartnerStatus status;

    @Column(name = "full_name", length = 150)
    private String fullName;

    @Column(length = 254)
    private String email;

    @Column(length = 20)
    private String phone;

    @Column(length = 100)
    private String city;

    @Enumerated(EnumType.STRING)
    @Column(name = "vehicle_type", length = 20)
    private VehicleType vehicleType;

    @Column(name = "vehicle_number", length = 15)
    private String vehicleNumber;

    @Column(name = "driving_license_number", length = 20)
    private String drivingLicenseNumber;

    @Column(name = "status_reason", length = 500)
    private String statusReason;

    @Column(name = "submitted_at")
    private Instant submittedAt;

    @Column(name = "approved_at")
    private Instant approvedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    // ---- Phase 9: availability and live location (dispatch). Never moves updatedAt (the profile's last change). ----

    /** The partner's own switch; only an APPROVED partner can turn it on. */
    @Column(nullable = false)
    private boolean online;

    /** The ASSIGNED / PICKED_UP delivery; null when free. Changed only under the delivery's and this row's lock. */
    @Column(name = "active_delivery_id")
    private UUID activeDeliveryId;

    @Column(name = "last_latitude")
    private Double lastLatitude;

    @Column(name = "last_longitude")
    private Double lastLongitude;

    @Column(name = "last_heading")
    private Double lastHeading;

    @Column(name = "last_speed_kmh")
    private Double lastSpeedKmh;

    @Column(name = "last_accuracy_meters")
    private Double lastAccuracyMeters;

    /** When the last location report was stored (freshness and the 2 s throttle). */
    @Column(name = "last_location_at")
    private Instant lastLocationAt;

    @Version
    private long version;

    protected DeliveryPartner() {
    }

    /** New partner profile created when a user registers with the DELIVERY_PARTNER role. */
    public static DeliveryPartner draft(UUID userId, String fullName, String email, String phone, Instant now) {
        DeliveryPartner partner = new DeliveryPartner();
        partner.id = userId;
        partner.status = PartnerStatus.DRAFT;
        partner.fullName = fullName;
        partner.email = email;
        partner.phone = phone;
        partner.createdAt = now;
        partner.updatedAt = now;
        return partner;
    }

    /** Replaces the editable details (not the email). Allowed only before approval (DRAFT) or after a rejection. */
    public void updateDetails(String fullName, String phone, String city, VehicleType vehicleType,
                              String vehicleNumber, String drivingLicenseNumber, Instant now) {
        if (status != PartnerStatus.DRAFT && status != PartnerStatus.REJECTED) {
            throw new ConflictException("PROFILE_LOCKED",
                    "The partner profile can only be edited while in DRAFT or REJECTED status (current: " + status + ")");
        }
        this.fullName = fullName;
        this.phone = phone;
        this.city = city;
        this.vehicleType = vehicleType;
        this.vehicleNumber = vehicleNumber;
        this.drivingLicenseNumber = drivingLicenseNumber;
        this.updatedAt = now;
    }

    public PartnerStatus submit(Instant now) {
        List<Map<String, String>> missing = missingForSubmission();
        if (!missing.isEmpty()) {
            requireStatus(EnumSet.of(PartnerStatus.DRAFT, PartnerStatus.REJECTED), PartnerStatus.PENDING_APPROVAL);
            throw new BusinessRuleException("PROFILE_INCOMPLETE", "Complete the required fields before submitting.", missing);
        }
        PartnerStatus from = moveTo(PartnerStatus.PENDING_APPROVAL, EnumSet.of(PartnerStatus.DRAFT, PartnerStatus.REJECTED), null, now);
        submittedAt = now;
        return from;
    }

    public PartnerStatus approve(Instant now) {
        PartnerStatus from = moveTo(PartnerStatus.APPROVED, EnumSet.of(PartnerStatus.PENDING_APPROVAL), null, now);
        approvedAt = now;
        return from;
    }

    public PartnerStatus reject(String reason, Instant now) {
        return moveTo(PartnerStatus.REJECTED, EnumSet.of(PartnerStatus.PENDING_APPROVAL), reason, now);
    }

    public PartnerStatus suspend(String reason, Instant now) {
        return moveTo(PartnerStatus.SUSPENDED, EnumSet.of(PartnerStatus.APPROVED), reason, now);
    }

    public PartnerStatus reinstate(Instant now) {
        return moveTo(PartnerStatus.APPROVED, EnumSet.of(PartnerStatus.SUSPENDED), null, now);
    }

    /**
     * Field-level problems that block submission, in the same shape as validation errors. Vehicle
     * number and driving licence are required unless the vehicle is a BICYCLE.
     */
    public List<Map<String, String>> missingForSubmission() {
        List<Map<String, String>> missing = new ArrayList<>();
        requireText(missing, "fullName", fullName);
        requireText(missing, "phone", phone);
        requireText(missing, "city", city);
        if (vehicleType == null) {
            missing.add(Map.of("field", "vehicleType", "message", "is required"));
        }
        if (vehicleType == null || vehicleType.isMotorised()) {
            requireText(missing, "vehicleNumber", vehicleNumber, "is required unless the vehicle is a BICYCLE");
            requireText(missing, "drivingLicenseNumber", drivingLicenseNumber, "is required unless the vehicle is a BICYCLE");
        }
        return missing;
    }

    // ---- availability (Phase 9); callers hold this row's lock and have checked isApproved() --------------------

    public boolean isApproved() {
        return status == PartnerStatus.APPROVED;
    }

    public void setOnline(boolean online) {
        this.online = online;
    }

    /** Stores a location report as the partner's last location. */
    public void recordLocation(double latitude, double longitude, Double heading, Double speedKmh,
                               Double accuracyMeters, Instant at) {
        this.lastLatitude = latitude;
        this.lastLongitude = longitude;
        this.lastHeading = heading;
        this.lastSpeedKmh = speedKmh;
        this.lastAccuracyMeters = accuracyMeters;
        this.lastLocationAt = at;
    }

    public boolean hasLocation() {
        return lastLatitude != null && lastLongitude != null && lastLocationAt != null;
    }

    /** The partner now delivers {@code deliveryId} (accepted offer or manual assignment). */
    public void startDelivery(UUID deliveryId) {
        this.activeDeliveryId = deliveryId;
    }

    /** Frees the partner if {@code deliveryId} is their active delivery (delivered, failed, cancelled, unassigned). */
    public void finishDelivery(UUID deliveryId) {
        if (deliveryId.equals(activeDeliveryId)) {
            this.activeDeliveryId = null;
        }
    }

    /**
     * Available for an offer (contract §2), apart from "no other pending offer", which the caller checks: APPROVED,
     * online, free, and a stored location not older than {@code freshSince}.
     */
    public boolean isAvailable(Instant freshSince) {
        return isApproved() && online && activeDeliveryId == null && hasLocation()
                && !lastLocationAt.isBefore(freshSince);
    }

    private PartnerStatus moveTo(PartnerStatus target, Set<PartnerStatus> allowedFrom, String reason, Instant now) {
        requireStatus(allowedFrom, target);
        PartnerStatus from = status;
        status = target;
        statusReason = reason;
        updatedAt = now;
        return from;
    }

    private void requireStatus(Set<PartnerStatus> allowedFrom, PartnerStatus target) {
        if (!allowedFrom.contains(status)) {
            throw new ConflictException("INVALID_STATUS_TRANSITION",
                    "Cannot move delivery partner from " + status + " to " + target);
        }
    }

    private static void requireText(List<Map<String, String>> missing, String field, String value) {
        requireText(missing, field, value, "is required");
    }

    private static void requireText(List<Map<String, String>> missing, String field, String value, String message) {
        if (value == null || value.isBlank()) {
            missing.add(Map.of("field", field, "message", message));
        }
    }

    public UUID getId() {
        return id;
    }

    public PartnerStatus getStatus() {
        return status;
    }

    public String getFullName() {
        return fullName;
    }

    public String getEmail() {
        return email;
    }

    public String getPhone() {
        return phone;
    }

    public String getCity() {
        return city;
    }

    public VehicleType getVehicleType() {
        return vehicleType;
    }

    public String getVehicleNumber() {
        return vehicleNumber;
    }

    public String getDrivingLicenseNumber() {
        return drivingLicenseNumber;
    }

    public String getStatusReason() {
        return statusReason;
    }

    public Instant getSubmittedAt() {
        return submittedAt;
    }

    public Instant getApprovedAt() {
        return approvedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public boolean isOnline() {
        return online;
    }

    public UUID getActiveDeliveryId() {
        return activeDeliveryId;
    }

    public Double getLastLatitude() {
        return lastLatitude;
    }

    public Double getLastLongitude() {
        return lastLongitude;
    }

    public Double getLastHeading() {
        return lastHeading;
    }

    public Double getLastSpeedKmh() {
        return lastSpeedKmh;
    }

    public Double getLastAccuracyMeters() {
        return lastAccuracyMeters;
    }

    public Instant getLastLocationAt() {
        return lastLocationAt;
    }
}
