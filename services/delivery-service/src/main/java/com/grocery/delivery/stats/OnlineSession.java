package com.grocery.delivery.stats;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * One stretch of time a delivery partner was online (table {@code partner_online_session}): from going online to going
 * offline, or to their last location report when the phone went quiet. Open while {@code endedAt} is null.
 */
@Entity
@Table(name = "partner_online_session")
public class OnlineSession {

    @Id
    private UUID id;

    @Column(name = "partner_id", nullable = false, updatable = false)
    private UUID partnerId;

    @Column(name = "started_at", nullable = false, updatable = false)
    private Instant startedAt;

    @Column(name = "last_seen_at", nullable = false)
    private Instant lastSeenAt;

    @Column(name = "ended_at")
    private Instant endedAt;

    protected OnlineSession() {
    }

    public OnlineSession(UUID id, UUID partnerId, Instant startedAt) {
        this.id = id;
        this.partnerId = partnerId;
        this.startedAt = startedAt;
        this.lastSeenAt = startedAt;
    }

    public void seen(Instant at) {
        lastSeenAt = at;
    }

    public void end(Instant at) {
        endedAt = at;
    }

    /** When it ended: going offline, else (open, or never closed) its last sign of life. */
    public Instant effectiveEnd() {
        return endedAt != null ? endedAt : lastSeenAt;
    }

    /** How much of it falls inside [from, to). */
    public Duration overlap(Instant from, Instant to) {
        Instant start = startedAt.isAfter(from) ? startedAt : from;
        Instant end = effectiveEnd().isBefore(to) ? effectiveEnd() : to;
        return end.isAfter(start) ? Duration.between(start, end) : Duration.ZERO;
    }

    public UUID getId() {
        return id;
    }

    public UUID getPartnerId() {
        return partnerId;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public Instant getLastSeenAt() {
        return lastSeenAt;
    }

    public Instant getEndedAt() {
        return endedAt;
    }
}
