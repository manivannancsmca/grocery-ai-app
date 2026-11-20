package com.grocery.customer.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;
import java.util.UUID;

/**
 * A delivery address in a customer's address book. Rules that span the whole book (limit, exactly one
 * default) live in {@code AddressBookService}, which changes addresses only while holding the
 * customer row lock.
 */
@Entity
@Table(name = "customer_address")
public class CustomerAddress {

    @Id
    private UUID id;

    @Column(name = "customer_id", nullable = false)
    private UUID customerId;

    @Column(nullable = false, length = 50)
    private String label;

    @Column(name = "recipient_name", nullable = false, length = 120)
    private String recipientName;

    @Column(nullable = false, length = 20)
    private String phone;

    @Column(name = "line1", nullable = false, length = 200)
    private String line1;

    @Column(name = "line2", length = 200)
    private String line2;

    @Column(length = 200)
    private String landmark;

    @Column(nullable = false, length = 100)
    private String city;

    @Column(nullable = false, length = 100)
    private String state;

    @Column(name = "postal_code", nullable = false, length = 10)
    private String postalCode;

    @Column(nullable = false, length = 2)
    private String country;

    private Double latitude;

    private Double longitude;

    @Column(name = "is_default", nullable = false)
    private boolean defaultAddress;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    protected CustomerAddress() {
    }

    /** A new, not-yet-default address; fill it with {@link #updateDetails} before saving. */
    public static CustomerAddress create(UUID id, UUID customerId, Instant now) {
        CustomerAddress address = new CustomerAddress();
        address.id = id;
        address.customerId = customerId;
        address.createdAt = now;
        address.updatedAt = now;
        return address;
    }

    /** Replaces every field the customer can edit (PUT semantics). */
    public void updateDetails(String label, String recipientName, String phone, String line1, String line2,
                              String landmark, String city, String state, String postalCode, String country,
                              Double latitude, Double longitude, Instant now) {
        this.label = label;
        this.recipientName = recipientName;
        this.phone = phone;
        this.line1 = line1;
        this.line2 = line2;
        this.landmark = landmark;
        this.city = city;
        this.state = state;
        this.postalCode = postalCode;
        this.country = country;
        this.latitude = latitude;
        this.longitude = longitude;
        this.updatedAt = now;
    }

    /** Sets the default flag; {@code updatedAt} moves only when the flag actually changes. */
    public void setDefault(boolean value, Instant now) {
        if (defaultAddress != value) {
            defaultAddress = value;
            updatedAt = now;
        }
    }

    public UUID getId() {
        return id;
    }

    public UUID getCustomerId() {
        return customerId;
    }

    public String getLabel() {
        return label;
    }

    public String getRecipientName() {
        return recipientName;
    }

    public String getPhone() {
        return phone;
    }

    public String getLine1() {
        return line1;
    }

    public String getLine2() {
        return line2;
    }

    public String getLandmark() {
        return landmark;
    }

    public String getCity() {
        return city;
    }

    public String getState() {
        return state;
    }

    public String getPostalCode() {
        return postalCode;
    }

    public String getCountry() {
        return country;
    }

    public Double getLatitude() {
        return latitude;
    }

    public Double getLongitude() {
        return longitude;
    }

    public boolean isDefaultAddress() {
        return defaultAddress;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
