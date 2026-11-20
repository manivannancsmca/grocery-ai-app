package com.grocery.restaurant.entity;

import com.grocery.common.web.BusinessRuleException;
import com.grocery.common.web.ConflictException;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OrderColumn;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import jakarta.persistence.Version;
import org.hibernate.annotations.BatchSize;

import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * A restaurant and its approval lifecycle. All status rules live here; services only load, call a
 * method, save, record history and publish the event. Menu categories and items (Phase 3) are separate
 * entities that reference this aggregate by id; this row carries the listing-wide state (accepting-orders
 * switch, listing version) and its row lock serialises every change to the public listing
 * (see {@code service.RestaurantListingPublisher}).
 */
@Entity
@Table(name = "restaurant")
public class Restaurant {

    @Id
    private UUID id;

    @Column(name = "owner_user_id", nullable = false, unique = true)
    private UUID ownerUserId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private RestaurantStatus status;

    @Column(length = 150)
    private String name;

    @Column(length = 1000)
    private String description;

    /** Owner's order is kept (first = primary cuisine). Batch-loaded for admin result pages. */
    @ElementCollection
    @CollectionTable(name = "restaurant_cuisine", joinColumns = @JoinColumn(name = "restaurant_id"))
    @OrderColumn(name = "sort_order")
    @Column(name = "cuisine", nullable = false, length = 30)
    @BatchSize(size = 50)
    private List<String> cuisines = new ArrayList<>();

    @Column(name = "fssai_license", length = 14)
    private String fssaiLicense;

    @Column(length = 15)
    private String gstin;

    @Column(name = "contact_email", length = 254)
    private String contactEmail;

    @Column(name = "contact_phone", length = 20)
    private String contactPhone;

    @Embedded
    private BusinessAddress address;

    /** At most one entry per day (primary key restaurant_id + day_of_week). */
    @ElementCollection
    @CollectionTable(name = "restaurant_opening_hours", joinColumns = @JoinColumn(name = "restaurant_id"))
    @BatchSize(size = 50)
    private List<OpeningHours> openingHours = new ArrayList<>();

    @Column(name = "status_reason", length = 500)
    private String statusReason;

    @Column(name = "submitted_at")
    private Instant submittedAt;

    /** First approval; never cleared. Only ever-approved restaurants have a public listing. */
    @Column(name = "approved_at")
    private Instant approvedAt;

    /** The owner's "temporarily stop new orders" switch. The restaurant stays visible either way. */
    @Column(name = "accepting_orders", nullable = false)
    private boolean acceptingOrders = true;

    /** Version of the last published RestaurantListingUpdated snapshot (0 = never published). */
    @Column(name = "listing_version", nullable = false)
    private long listingVersion;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    /** Last change of the profile, the status or (once approved) anything in the public listing. */
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    /**
     * True when this instance was loaded with {@code SELECT … FOR UPDATE} in the current transaction
     * (instances never outlive their transaction here). Guards the listing locking rule.
     */
    @Transient
    private boolean lockedForUpdate;

    protected Restaurant() {
    }

    /** New restaurant profile created when a user registers with the RESTAURANT_OWNER role. */
    public static Restaurant draft(UUID id, UUID ownerUserId, String name, String contactEmail,
                                   String contactPhone, Instant now) {
        Restaurant restaurant = new Restaurant();
        restaurant.id = id;
        restaurant.ownerUserId = ownerUserId;
        restaurant.status = RestaurantStatus.DRAFT;
        restaurant.name = name;
        restaurant.contactEmail = contactEmail;
        restaurant.contactPhone = contactPhone;
        restaurant.createdAt = now;
        restaurant.updatedAt = now;
        return restaurant;
    }

    /** Replaces the editable details. Allowed only before approval (DRAFT) or after a rejection. */
    public void updateDetails(String name, String description, List<String> cuisines, String fssaiLicense,
                              String gstin, String contactEmail, String contactPhone, BusinessAddress address,
                              List<OpeningHours> openingHours, Instant now) {
        if (status != RestaurantStatus.DRAFT && status != RestaurantStatus.REJECTED) {
            throw new ConflictException("PROFILE_LOCKED",
                    "The restaurant profile can only be edited while in DRAFT or REJECTED status (current: " + status + ")");
        }
        this.name = name;
        this.description = description;
        this.cuisines.clear();
        this.cuisines.addAll(cuisines);
        this.fssaiLicense = fssaiLicense;
        this.gstin = gstin;
        this.contactEmail = contactEmail;
        this.contactPhone = contactPhone;
        this.address = address;
        this.openingHours.clear();
        this.openingHours.addAll(openingHours);
        this.updatedAt = now;
    }

    public RestaurantStatus submit(Instant now) {
        List<Map<String, String>> missing = missingForSubmission();
        if (!missing.isEmpty()) {
            requireStatus(EnumSet.of(RestaurantStatus.DRAFT, RestaurantStatus.REJECTED), RestaurantStatus.PENDING_APPROVAL);
            throw new BusinessRuleException("PROFILE_INCOMPLETE", "Complete the required fields before submitting.", missing);
        }
        RestaurantStatus from = moveTo(RestaurantStatus.PENDING_APPROVAL,
                EnumSet.of(RestaurantStatus.DRAFT, RestaurantStatus.REJECTED), null, now);
        submittedAt = now;
        return from;
    }

    public RestaurantStatus approve(Instant now) {
        RestaurantStatus from = moveTo(RestaurantStatus.APPROVED, EnumSet.of(RestaurantStatus.PENDING_APPROVAL), null, now);
        approvedAt = now;
        return from;
    }

    public RestaurantStatus reject(String reason, Instant now) {
        return moveTo(RestaurantStatus.REJECTED, EnumSet.of(RestaurantStatus.PENDING_APPROVAL), reason, now);
    }

    public RestaurantStatus suspend(String reason, Instant now) {
        return moveTo(RestaurantStatus.SUSPENDED, EnumSet.of(RestaurantStatus.APPROVED), reason, now);
    }

    public RestaurantStatus reinstate(Instant now) {
        return moveTo(RestaurantStatus.APPROVED, EnumSet.of(RestaurantStatus.SUSPENDED), null, now);
    }

    /** Owners may build and change their menu in any status except SUSPENDED. */
    public void requireOwnerChangesAllowed() {
        if (status == RestaurantStatus.SUSPENDED) {
            throw new ConflictException("RESTAURANT_SUSPENDED",
                    "The restaurant is suspended: its menu and order settings cannot be changed");
        }
    }

    /** @return false when the switch already had this value (nothing changed). */
    public boolean changeAcceptingOrders(boolean accepting, Instant now) {
        requireOwnerChangesAllowed();
        if (acceptingOrders == accepting) {
            return false;
        }
        acceptingOrders = accepting;
        updatedAt = now;
        return true;
    }

    /** Ever approved = has a public listing (also while suspended: the listing then says SUSPENDED). */
    public boolean isListed() {
        return approvedAt != null;
    }

    /**
     * Next version of the public listing; call only while holding this row's lock, once per transaction,
     * after the change was made.
     */
    public long advanceListingVersion(Instant now) {
        if (!isListed()) {
            throw new IllegalStateException("Restaurant " + id + " has no public listing yet");
        }
        listingVersion++;
        updatedAt = now;
        return listingVersion;
    }

    public void markLockedForUpdate() {
        lockedForUpdate = true;
    }

    public boolean isLockedForUpdate() {
        return lockedForUpdate;
    }

    /** Field-level problems that block submission, in the same shape as validation errors. */
    public List<Map<String, String>> missingForSubmission() {
        List<Map<String, String>> missing = new ArrayList<>();
        requireText(missing, "name", name);
        if (cuisines.isEmpty()) {
            missing.add(Map.of("field", "cuisines", "message", "at least one cuisine is required"));
        }
        requireText(missing, "fssaiLicense", fssaiLicense);
        requireText(missing, "contactEmail", contactEmail);
        requireText(missing, "contactPhone", contactPhone);
        if (address == null) {
            missing.add(Map.of("field", "address", "message", "is required"));
        } else {
            requireText(missing, "address.line1", address.getLine1());
            requireText(missing, "address.city", address.getCity());
            requireText(missing, "address.state", address.getState());
            requireText(missing, "address.postalCode", address.getPostalCode());
            if (address.getLatitude() == null || address.getLongitude() == null) {
                missing.add(Map.of("field", "address.latitude", "message", "restaurant location (latitude/longitude) is required"));
            }
        }
        if (openingHours.isEmpty()) {
            missing.add(Map.of("field", "openingHours", "message", "opening hours for at least one day are required"));
        }
        return missing;
    }

    private RestaurantStatus moveTo(RestaurantStatus target, Set<RestaurantStatus> allowedFrom, String reason, Instant now) {
        requireStatus(allowedFrom, target);
        RestaurantStatus from = status;
        status = target;
        statusReason = reason;
        updatedAt = now;
        return from;
    }

    private void requireStatus(Set<RestaurantStatus> allowedFrom, RestaurantStatus target) {
        if (!allowedFrom.contains(status)) {
            throw new ConflictException("INVALID_STATUS_TRANSITION",
                    "Cannot move restaurant from " + status + " to " + target);
        }
    }

    private static void requireText(List<Map<String, String>> missing, String field, String value) {
        if (value == null || value.isBlank()) {
            missing.add(Map.of("field", field, "message", "is required"));
        }
    }

    public UUID getId() {
        return id;
    }

    public UUID getOwnerUserId() {
        return ownerUserId;
    }

    public RestaurantStatus getStatus() {
        return status;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public List<String> getCuisines() {
        return List.copyOf(cuisines);
    }

    public String getFssaiLicense() {
        return fssaiLicense;
    }

    public String getGstin() {
        return gstin;
    }

    public String getContactEmail() {
        return contactEmail;
    }

    public String getContactPhone() {
        return contactPhone;
    }

    public BusinessAddress getAddress() {
        return address;
    }

    public List<OpeningHours> getOpeningHours() {
        return List.copyOf(openingHours);
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

    public boolean isAcceptingOrders() {
        return acceptingOrders;
    }

    public long getListingVersion() {
        return listingVersion;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
