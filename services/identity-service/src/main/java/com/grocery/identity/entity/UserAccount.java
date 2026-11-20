package com.grocery.identity.entity;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * A login identity. Business rules about the account (lockout, suspension) live here so the
 * services stay thin.
 */
@Entity
@Table(name = "user_account")
public class UserAccount {

    @Id
    private UUID id;

    @Column(nullable = false, unique = true, length = 254)
    private String email;

    @Column(length = 20)
    private String phone;

    @Column(name = "password_hash", nullable = false, length = 100)
    private String passwordHash;

    @Column(name = "full_name", nullable = false, length = 120)
    private String fullName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private UserStatus status;

    @Column(name = "suspension_reason", length = 500)
    private String suspensionReason;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "user_role", joinColumns = @JoinColumn(name = "user_id"))
    @Column(name = "role", length = 30, nullable = false)
    private Set<String> roles = new HashSet<>();

    @Column(name = "failed_login_count", nullable = false)
    private int failedLoginCount;

    @Column(name = "locked_until")
    private Instant lockedUntil;

    @Column(name = "last_login_at")
    private Instant lastLoginAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** {@code updatedAt} of the last customer profile applied (CustomerProfileUpdated); null = none yet. */
    @Column(name = "profile_updated_at")
    private Instant profileUpdatedAt;

    @Version
    private long version;

    protected UserAccount() {
    }

    public static UserAccount create(UUID id, String email, String phone, String passwordHash,
                                     String fullName, Set<String> roles, Instant now) {
        UserAccount user = new UserAccount();
        user.id = id;
        user.email = email;
        user.phone = phone;
        user.passwordHash = passwordHash;
        user.fullName = fullName;
        user.roles = new HashSet<>(roles);
        user.status = UserStatus.ACTIVE;
        user.createdAt = now;
        user.updatedAt = now;
        return user;
    }

    public boolean isLocked(Instant now) {
        return lockedUntil != null && lockedUntil.isAfter(now);
    }

    /** Counts a wrong password; locks the account temporarily after too many in a row. */
    public void recordFailedLogin(int maxAttempts, Duration lockDuration, Instant now) {
        failedLoginCount++;
        if (failedLoginCount >= maxAttempts) {
            lockedUntil = now.plus(lockDuration);
            failedLoginCount = 0;
        }
        updatedAt = now;
    }

    public void recordSuccessfulLogin(Instant now) {
        failedLoginCount = 0;
        lockedUntil = null;
        lastLoginAt = now;
        updatedAt = now;
    }

    /**
     * A new password (changed while signed in, or with a reset link). Whoever set it proved they own the account, so a
     * running lockout ends too.
     */
    public void changePassword(String newPasswordHash, Instant now) {
        passwordHash = newPasswordHash;
        failedLoginCount = 0;
        lockedUntil = null;
        updatedAt = now;
    }

    /**
     * The name and phone of the customer's profile (customer-service is their owner once the customer edits them).
     * An event older than the last one applied changes nothing.
     *
     * @return whether the profile was applied
     */
    public boolean applyCustomerProfile(String newFullName, String newPhone, Instant profileUpdated, Instant now) {
        if (profileUpdatedAt != null && !profileUpdated.isAfter(profileUpdatedAt)) {
            return false;
        }
        fullName = newFullName;
        phone = newPhone;
        profileUpdatedAt = profileUpdated;
        updatedAt = now;
        return true;
    }

    public void suspend(String reason, Instant now) {
        status = UserStatus.SUSPENDED;
        suspensionReason = reason;
        updatedAt = now;
    }

    public void reactivate(Instant now) {
        status = UserStatus.ACTIVE;
        suspensionReason = null;
        lockedUntil = null;
        failedLoginCount = 0;
        updatedAt = now;
    }

    public boolean isSuspended() {
        return status == UserStatus.SUSPENDED;
    }

    public UUID getId() {
        return id;
    }

    public String getEmail() {
        return email;
    }

    public String getPhone() {
        return phone;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public String getFullName() {
        return fullName;
    }

    public UserStatus getStatus() {
        return status;
    }

    public String getSuspensionReason() {
        return suspensionReason;
    }

    public Set<String> getRoles() {
        return Set.copyOf(roles);
    }

    public Instant getLastLoginAt() {
        return lastLoginAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
