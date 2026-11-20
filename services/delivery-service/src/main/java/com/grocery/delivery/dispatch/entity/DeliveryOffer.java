package com.grocery.delivery.dispatch.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;
import java.util.UUID;

/**
 * An offer of a delivery to one partner, valid until {@code expiresAt}. Changed only while the caller holds the lock
 * of its delivery's row, so an accept, a rejection, the expiry poller and a withdrawal never overlap.
 */
@Entity
@Table(name = "delivery_offer")
public class DeliveryOffer {

    @Id
    private UUID id;

    @Column(name = "delivery_id", nullable = false, updatable = false)
    private UUID deliveryId;

    @Column(name = "partner_id", nullable = false, updatable = false)
    private UUID partnerId;

    @Column(name = "search_round", nullable = false, updatable = false)
    private int searchRound;

    @Column(nullable = false, updatable = false)
    private int wave;

    /** Partner → pickup point when offered, km (straight line). */
    @Column(name = "distance_km", nullable = false, updatable = false)
    private double distanceKm;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private OfferStatus status;

    @Column(name = "sent_at", nullable = false, updatable = false)
    private Instant sentAt;

    @Column(name = "expires_at", nullable = false, updatable = false)
    private Instant expiresAt;

    @Column(name = "closed_at")
    private Instant closedAt;

    @Version
    private long version;

    protected DeliveryOffer() {
    }

    public DeliveryOffer(UUID id, UUID deliveryId, UUID partnerId, int searchRound, int wave, double distanceKm,
                         Instant sentAt, Instant expiresAt) {
        this.id = id;
        this.deliveryId = deliveryId;
        this.partnerId = partnerId;
        this.searchRound = searchRound;
        this.wave = wave;
        this.distanceKm = distanceKm;
        this.status = OfferStatus.PENDING;
        this.sentAt = sentAt;
        this.expiresAt = expiresAt;
    }

    /** PENDING and not yet past {@code expiresAt}. */
    public boolean isOpen(Instant now) {
        return status == OfferStatus.PENDING && expiresAt.isAfter(now);
    }

    /** The status a partner sees: a PENDING offer past its expiry is EXPIRED, even before the poller closes it. */
    public OfferStatus effectiveStatus(Instant now) {
        return status == OfferStatus.PENDING && !expiresAt.isAfter(now) ? OfferStatus.EXPIRED : status;
    }

    /** PENDING → ACCEPTED / REJECTED / EXPIRED / WITHDRAWN. */
    public void close(OfferStatus outcome, Instant now) {
        if (status != OfferStatus.PENDING) {
            throw new IllegalStateException("Offer " + id + " is already " + status);
        }
        if (outcome == OfferStatus.PENDING) {
            throw new IllegalArgumentException("An offer cannot be closed as PENDING");
        }
        this.status = outcome;
        this.closedAt = now;
    }

    public UUID getId() {
        return id;
    }

    public UUID getDeliveryId() {
        return deliveryId;
    }

    public UUID getPartnerId() {
        return partnerId;
    }

    public int getSearchRound() {
        return searchRound;
    }

    public int getWave() {
        return wave;
    }

    public double getDistanceKm() {
        return distanceKm;
    }

    public OfferStatus getStatus() {
        return status;
    }

    public Instant getSentAt() {
        return sentAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public Instant getClosedAt() {
        return closedAt;
    }
}
