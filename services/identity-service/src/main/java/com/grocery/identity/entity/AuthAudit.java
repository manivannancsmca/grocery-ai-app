package com.grocery.identity.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/** Security audit trail: logins, failures, lockouts, refresh-token reuse, suspensions. */
@Entity
@Table(name = "auth_audit")
public class AuthAudit {

    @Id
    private UUID id;

    @Column(name = "user_id")
    private UUID userId;

    @Column(length = 254)
    private String email;

    @Column(name = "event_type", nullable = false, length = 40)
    private String eventType;

    @Column(length = 500)
    private String detail;

    @Column(name = "ip_address", length = 64)
    private String ipAddress;

    @Column(name = "user_agent", length = 255)
    private String userAgent;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    protected AuthAudit() {
    }

    public AuthAudit(UUID id, UUID userId, String email, String eventType, String detail,
                     String ipAddress, String userAgent, Instant occurredAt) {
        this.id = id;
        this.userId = userId;
        this.email = email;
        this.eventType = eventType;
        this.detail = detail;
        this.ipAddress = ipAddress;
        this.userAgent = userAgent;
        this.occurredAt = occurredAt;
    }

    public String getEventType() {
        return eventType;
    }

    public UUID getUserId() {
        return userId;
    }
}
