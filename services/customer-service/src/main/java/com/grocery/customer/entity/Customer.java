package com.grocery.customer.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;
import java.util.UUID;

/**
 * A customer's profile. The id IS the identity user id (JWT {@code sub}), so every service can
 * use the user id from the token as the customer id without a lookup.
 */
@Entity
@Table(name = "customer")
public class Customer {

    @Id
    private UUID id;

    @Column(nullable = false, length = 254)
    private String email;

    @Column(name = "full_name", nullable = false, length = 120)
    private String fullName;

    @Column(length = 20)
    private String phone;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    protected Customer() {
    }

    /** New profile, from the registration event or created on demand from the caller's JWT. */
    public static Customer create(UUID userId, String email, String fullName, String phone, Instant now) {
        Customer customer = new Customer();
        customer.id = userId;
        customer.email = email;
        customer.fullName = fullName;
        customer.phone = phone;
        customer.createdAt = now;
        customer.updatedAt = now;
        return customer;
    }

    /** Replaces the editable details. The email is owned by identity-service and never changes here. */
    public void updateDetails(String fullName, String phone, Instant now) {
        this.fullName = fullName;
        this.phone = phone;
        this.updatedAt = now;
    }

    public UUID getId() {
        return id;
    }

    public String getEmail() {
        return email;
    }

    public String getFullName() {
        return fullName;
    }

    public String getPhone() {
        return phone;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
