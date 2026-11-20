package com.grocery.order.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

import java.util.UUID;

/** The delivery address as it was at checkout (customer-service may change or delete it later). */
@Embeddable
public class DeliveryAddress {

    @Column(name = "address_id", updatable = false)
    private UUID addressId;

    @Column(name = "address_label", length = 40, updatable = false)
    private String label;

    @Column(name = "recipient_name", length = 100, updatable = false)
    private String recipientName;

    @Column(length = 20, updatable = false)
    private String phone;

    @Column(length = 200, updatable = false)
    private String line1;

    @Column(length = 200, updatable = false)
    private String line2;

    @Column(length = 200, updatable = false)
    private String landmark;

    @Column(length = 100, updatable = false)
    private String city;

    @Column(length = 100, updatable = false)
    private String state;

    @Column(name = "postal_code", length = 20, updatable = false)
    private String postalCode;

    @Column(updatable = false)
    private Double latitude;

    @Column(updatable = false)
    private Double longitude;

    protected DeliveryAddress() {
    }

    public DeliveryAddress(UUID addressId, String label, String recipientName, String phone, String line1,
                           String line2, String landmark, String city, String state, String postalCode,
                           Double latitude, Double longitude) {
        this.addressId = addressId;
        this.label = label;
        this.recipientName = recipientName;
        this.phone = phone;
        this.line1 = line1;
        this.line2 = line2;
        this.landmark = landmark;
        this.city = city;
        this.state = state;
        this.postalCode = postalCode;
        this.latitude = latitude;
        this.longitude = longitude;
    }

    public UUID getAddressId() {
        return addressId;
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

    public Double getLatitude() {
        return latitude;
    }

    public Double getLongitude() {
        return longitude;
    }
}
