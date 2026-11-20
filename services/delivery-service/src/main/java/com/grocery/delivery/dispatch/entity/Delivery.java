package com.grocery.delivery.dispatch.entity;

import com.grocery.delivery.dispatch.geo.GeoPoint;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OrderColumn;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * The delivery of one order (contract §2): the snapshot taken from {@code OrderConfirmed} (pickup point, drop address,
 * recipient, note, item count, total — never changed afterwards), the delivery status with its history, the search
 * state of the assignment and the outcome.
 *
 * <p>The transitions check the current status and throw {@link IllegalStateException} when called in the wrong one:
 * callers check the state guard first and answer the API's 409s. Every status change appends a history entry and
 * moves {@code updatedAt}. Callers hold the row lock.
 *
 * <h2>Search state (SEARCHING)</h2>
 * A search runs in <b>rounds</b>: round 1 starts when the store accepts, a new round every retry interval after
 * nobody was found inside the largest radius, and when an admin asks for a re-search. Within a round, partners
 * already offered this delivery are not offered it again. {@code searchRadiusIndex} is the radius of the last wave;
 * it equals the number of radii once the round is exhausted. {@code nextSearchAt} is when the poller looks at the
 * delivery next: the expiry of the open offers, the retry time, or "now".
 */
@Entity
@Table(name = "delivery")
public class Delivery {

    public static final String CURRENCY = "INR";

    @Id
    private UUID id;

    @Column(name = "order_id", nullable = false, updatable = false)
    private UUID orderId;

    @Column(name = "order_number", nullable = false, length = 20, updatable = false)
    private String orderNumber;

    @Column(name = "order_type", nullable = false, length = 20, updatable = false)
    private String orderType;

    @Column(name = "customer_id", nullable = false, updatable = false)
    private UUID customerId;

    @Column(name = "store_id", nullable = false, updatable = false)
    private UUID storeId;

    @Column(name = "store_name", length = 150, updatable = false)
    private String storeName;

    @Column(name = "store_city", length = 100, updatable = false)
    private String storeCity;

    @Column(name = "store_latitude", updatable = false)
    private Double storeLatitude;

    @Column(name = "store_longitude", updatable = false)
    private Double storeLongitude;

    @Column(name = "drop_recipient_name", length = 150, updatable = false)
    private String dropRecipientName;

    @Column(name = "drop_phone", length = 20, updatable = false)
    private String dropPhone;

    @Column(name = "drop_line1", length = 200, updatable = false)
    private String dropLine1;

    @Column(name = "drop_line2", length = 200, updatable = false)
    private String dropLine2;

    @Column(name = "drop_landmark", length = 200, updatable = false)
    private String dropLandmark;

    @Column(name = "drop_city", length = 100, updatable = false)
    private String dropCity;

    @Column(name = "drop_postal_code", length = 20, updatable = false)
    private String dropPostalCode;

    @Column(name = "drop_latitude", updatable = false)
    private Double dropLatitude;

    @Column(name = "drop_longitude", updatable = false)
    private Double dropLongitude;

    @Column(length = 300, updatable = false)
    private String note;

    @Column(name = "item_count", nullable = false, updatable = false)
    private int itemCount;

    @Column(nullable = false, precision = 12, scale = 2, updatable = false)
    private BigDecimal total;

    @Column(nullable = false, length = 3, updatable = false)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private DeliveryStatus status;

    @Column(name = "ready_for_pickup", nullable = false)
    private boolean readyForPickup;

    @Column(name = "ready_by")
    private Instant readyBy;

    @Column(name = "ready_at")
    private Instant readyAt;

    @Column(name = "partner_id")
    private UUID partnerId;

    @Column(name = "search_round", nullable = false)
    private int searchRound;

    @Column(name = "search_radius_index", nullable = false)
    private int searchRadiusIndex;

    @Column(name = "search_wave", nullable = false)
    private int searchWave;

    @Column(name = "offers_sent", nullable = false)
    private int offersSent;

    @Column(name = "assignment_failed", nullable = false)
    private boolean assignmentFailed;

    @Column(name = "assignment_failed_at")
    private Instant assignmentFailedAt;

    @Column(name = "next_search_at")
    private Instant nextSearchAt;

    @Column(name = "confirmed_at", updatable = false)
    private Instant confirmedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "searching_at")
    private Instant searchingAt;

    @Column(name = "assigned_at")
    private Instant assignedAt;

    @Column(name = "picked_up_at")
    private Instant pickedUpAt;

    @Column(name = "delivered_at")
    private Instant deliveredAt;

    @Column(name = "failed_at")
    private Instant failedAt;

    @Column(name = "cancelled_at")
    private Instant cancelledAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "failure_reason", length = 30)
    private FailureReason failureReason;

    @Column(name = "failure_note", length = 300)
    private String failureNote;

    @ElementCollection
    @CollectionTable(name = "delivery_status_history", joinColumns = @JoinColumn(name = "delivery_id"))
    @OrderColumn(name = "seq")
    private List<DeliveryStatusChange> history = new ArrayList<>();

    /** Partners an admin took this delivery away from: never offered it again. */
    @ElementCollection
    @CollectionTable(name = "delivery_excluded_partner", joinColumns = @JoinColumn(name = "delivery_id"))
    @Column(name = "partner_id", nullable = false)
    private Set<UUID> excludedPartners = new HashSet<>();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    protected Delivery() {
    }

    /** A paid order (OrderConfirmed): PENDING until the store accepts it. */
    public static Delivery pending(UUID id, OrderSnapshot order, Instant now) {
        Delivery delivery = new Delivery();
        delivery.id = id;
        delivery.orderId = order.orderId();
        delivery.orderNumber = order.orderNumber();
        delivery.orderType = order.orderType();
        delivery.customerId = order.customerId();
        delivery.storeId = order.storeId();
        delivery.storeName = order.storeName();
        delivery.storeCity = order.storeCity();
        delivery.storeLatitude = order.storeLatitude();
        delivery.storeLongitude = order.storeLongitude();
        delivery.dropRecipientName = order.recipientName();
        delivery.dropPhone = order.phone();
        delivery.dropLine1 = order.line1();
        delivery.dropLine2 = order.line2();
        delivery.dropLandmark = order.landmark();
        delivery.dropCity = order.city();
        delivery.dropPostalCode = order.postalCode();
        delivery.dropLatitude = order.dropLatitude();
        delivery.dropLongitude = order.dropLongitude();
        delivery.note = order.note();
        delivery.itemCount = order.itemCount();
        delivery.total = order.total();
        delivery.currency = CURRENCY;
        delivery.confirmedAt = order.confirmedAt();
        delivery.status = DeliveryStatus.PENDING;
        delivery.createdAt = now;
        delivery.updatedAt = now;
        delivery.history.add(new DeliveryStatusChange(DeliveryStatus.PENDING, null, null, null, now));
        return delivery;
    }

    // ---- order events -----------------------------------------------------------------------------------------

    /** The store accepted the order (OrderAccepted): PENDING → SEARCHING, round 1 due now. */
    public void startSearching(Instant readyBy, Instant now) {
        require(DeliveryStatus.PENDING, DeliveryStatus.SEARCHING);
        this.readyBy = readyBy;
        this.searchingAt = now;
        this.searchRound = 1;
        this.searchRadiusIndex = 0;
        this.nextSearchAt = now;
        moveTo(DeliveryStatus.SEARCHING, null, null, null, now);
    }

    /** The order is ready at the store (OrderReadyForPickup). Returns false if it already was. */
    public boolean markReadyForPickup(Instant readyAt, Instant now) {
        if (readyForPickup) {
            return false;
        }
        this.readyForPickup = true;
        this.readyAt = readyAt != null ? readyAt : now;
        this.updatedAt = now;
        return true;
    }

    /** The order was cancelled before pickup: PENDING / SEARCHING / ASSIGNED → CANCELLED. */
    public void cancel(String reason, Instant now) {
        if (!DeliveryStatus.CANCELLABLE.contains(status)) {
            throw new IllegalStateException("Cannot cancel a " + status + " delivery");
        }
        this.cancelledAt = now;
        this.nextSearchAt = null;
        moveTo(DeliveryStatus.CANCELLED, partnerId, reason, null, now);
    }

    // ---- search state (SEARCHING) -----------------------------------------------------------------------------

    /** The point partners are searched around: the store, or the drop point when the store location is unknown. */
    public GeoPoint searchPoint() {
        GeoPoint store = pickupPoint();
        return store != null ? store : dropPoint();
    }

    public GeoPoint pickupPoint() {
        return GeoPoint.of(storeLatitude, storeLongitude);
    }

    public GeoPoint dropPoint() {
        return GeoPoint.of(dropLatitude, dropLongitude);
    }

    /** All radii of the current round were searched without finding anyone. */
    public boolean isRoundExhausted(int radiusCount) {
        return searchRadiusIndex >= radiusCount;
    }

    /** A new round from the smallest radius, due now (retry after a failed search). */
    public void startNewRound(Instant now) {
        requireSearching();
        this.searchRound++;
        this.searchRadiusIndex = 0;
        this.nextSearchAt = now;
        this.updatedAt = now;
    }

    /** A wave of {@code offers} offers went out at radius {@code radiusIndex}; looked at again when they expire. */
    public void waveSent(int radiusIndex, int offers, Instant expiresAt, Instant now) {
        requireSearching();
        this.searchRadiusIndex = radiusIndex;
        this.searchWave++;
        this.offersSent += offers;
        this.nextSearchAt = expiresAt;
        this.updatedAt = now;
    }

    /** Open offers remain: looked at again when the first of them expires. */
    public void waitForOffers(Instant until) {
        this.nextSearchAt = until;
    }

    /** The search could not run (partner index unavailable): try again at {@code at}. */
    public void retrySearchAt(Instant at) {
        this.nextSearchAt = at;
    }

    /**
     * Nobody inside the largest radius: {@code assignmentFailed}, a fresh round at {@code retryAt}. Returns true the
     * first time this delivery fails (DeliveryAssignmentFailed is published once).
     */
    public boolean searchFailed(int radiusCount, Instant retryAt, Instant now) {
        requireSearching();
        this.searchRadiusIndex = radiusCount;
        this.assignmentFailed = true;
        this.nextSearchAt = retryAt;
        this.updatedAt = now;
        if (assignmentFailedAt == null) {
            this.assignmentFailedAt = now;
            return true;
        }
        return false;
    }

    // ---- assignment ---------------------------------------------------------------------------------------------

    /** SEARCHING (accepted offer or admin) or ASSIGNED (admin, another partner) → ASSIGNED to {@code partner}. */
    public void assign(UUID partner, boolean manual, UUID actorUserId, Instant now) {
        if (status != DeliveryStatus.SEARCHING && status != DeliveryStatus.ASSIGNED) {
            throw new IllegalStateException("Cannot assign a " + status + " delivery");
        }
        this.partnerId = partner;
        this.assignedAt = now;
        this.assignmentFailed = false;
        this.nextSearchAt = null;
        moveTo(DeliveryStatus.ASSIGNED, partner, manual ? "MANUAL" : null, actorUserId, now);
    }

    /**
     * An admin takes the delivery away from its partner: ASSIGNED → SEARCHING, the partner is never offered it again.
     * Returns the former partner. The caller starts the new search ({@link #restartSearch}).
     */
    public UUID unassign(String reason, UUID actorUserId, Instant now) {
        require(DeliveryStatus.ASSIGNED, DeliveryStatus.SEARCHING);
        UUID former = partnerId;
        this.excludedPartners.add(former);
        this.partnerId = null;
        this.assignedAt = null;
        moveTo(DeliveryStatus.SEARCHING, former, reason, actorUserId, now);
        return former;
    }

    /** An admin's "search again": a fresh round from the smallest radius, due now; clears assignmentFailed. */
    public void restartSearch(Instant now) {
        requireSearching();
        this.searchRound++;
        this.searchRadiusIndex = 0;
        this.assignmentFailed = false;
        this.nextSearchAt = now;
        this.updatedAt = now;
    }

    // ---- the partner's steps ----------------------------------------------------------------------------------

    public void pickUp(UUID actorUserId, Instant now) {
        require(DeliveryStatus.ASSIGNED, DeliveryStatus.PICKED_UP);
        this.pickedUpAt = now;
        moveTo(DeliveryStatus.PICKED_UP, partnerId, null, actorUserId, now);
    }

    public void deliver(UUID actorUserId, Instant now) {
        require(DeliveryStatus.PICKED_UP, DeliveryStatus.DELIVERED);
        this.deliveredAt = now;
        this.finishedAt = now;
        moveTo(DeliveryStatus.DELIVERED, partnerId, null, actorUserId, now);
    }

    public void fail(FailureReason reason, String note, UUID actorUserId, Instant now) {
        require(DeliveryStatus.PICKED_UP, DeliveryStatus.FAILED);
        this.failureReason = reason;
        this.failureNote = note;
        this.failedAt = now;
        this.finishedAt = now;
        moveTo(DeliveryStatus.FAILED, partnerId, reason.name(), actorUserId, now);
    }

    public boolean isAssignedTo(UUID partner) {
        return partnerId != null && partnerId.equals(partner);
    }

    private void moveTo(DeliveryStatus target, UUID partner, String reason, UUID actorUserId, Instant now) {
        this.status = target;
        this.updatedAt = now;
        this.history.add(new DeliveryStatusChange(target, partner, reason, actorUserId, now));
    }

    private void require(DeliveryStatus expected, DeliveryStatus target) {
        if (status != expected) {
            throw new IllegalStateException("Cannot move a " + status + " delivery to " + target);
        }
    }

    private void requireSearching() {
        if (status != DeliveryStatus.SEARCHING) {
            throw new IllegalStateException("The delivery is " + status + ", not SEARCHING");
        }
    }

    public UUID getId() {
        return id;
    }

    public UUID getOrderId() {
        return orderId;
    }

    public String getOrderNumber() {
        return orderNumber;
    }

    public String getOrderType() {
        return orderType;
    }

    public UUID getCustomerId() {
        return customerId;
    }

    public UUID getStoreId() {
        return storeId;
    }

    public String getStoreName() {
        return storeName;
    }

    public String getStoreCity() {
        return storeCity;
    }

    public Double getStoreLatitude() {
        return storeLatitude;
    }

    public Double getStoreLongitude() {
        return storeLongitude;
    }

    public String getDropRecipientName() {
        return dropRecipientName;
    }

    public String getDropPhone() {
        return dropPhone;
    }

    public String getDropLine1() {
        return dropLine1;
    }

    public String getDropLine2() {
        return dropLine2;
    }

    public String getDropLandmark() {
        return dropLandmark;
    }

    public String getDropCity() {
        return dropCity;
    }

    public String getDropPostalCode() {
        return dropPostalCode;
    }

    public Double getDropLatitude() {
        return dropLatitude;
    }

    public Double getDropLongitude() {
        return dropLongitude;
    }

    public String getNote() {
        return note;
    }

    public int getItemCount() {
        return itemCount;
    }

    public BigDecimal getTotal() {
        return total;
    }

    public String getCurrency() {
        return currency;
    }

    public DeliveryStatus getStatus() {
        return status;
    }

    public boolean isReadyForPickup() {
        return readyForPickup;
    }

    public Instant getReadyBy() {
        return readyBy;
    }

    public Instant getReadyAt() {
        return readyAt;
    }

    public UUID getPartnerId() {
        return partnerId;
    }

    public int getSearchRound() {
        return searchRound;
    }

    public int getSearchRadiusIndex() {
        return searchRadiusIndex;
    }

    public int getSearchWave() {
        return searchWave;
    }

    public int getOffersSent() {
        return offersSent;
    }

    public boolean isAssignmentFailed() {
        return assignmentFailed;
    }

    public Instant getAssignmentFailedAt() {
        return assignmentFailedAt;
    }

    public Instant getNextSearchAt() {
        return nextSearchAt;
    }

    public Instant getConfirmedAt() {
        return confirmedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getSearchingAt() {
        return searchingAt;
    }

    public Instant getAssignedAt() {
        return assignedAt;
    }

    public Instant getPickedUpAt() {
        return pickedUpAt;
    }

    public Instant getDeliveredAt() {
        return deliveredAt;
    }

    public Instant getFailedAt() {
        return failedAt;
    }

    public Instant getCancelledAt() {
        return cancelledAt;
    }

    public Instant getFinishedAt() {
        return finishedAt;
    }

    public FailureReason getFailureReason() {
        return failureReason;
    }

    public String getFailureNote() {
        return failureNote;
    }

    public List<DeliveryStatusChange> getHistory() {
        return Collections.unmodifiableList(history);
    }

    public Set<UUID> getExcludedPartners() {
        return Collections.unmodifiableSet(excludedPartners);
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
