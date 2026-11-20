package com.grocery.vendor.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

/** Vendor store / warehouse address; its coordinates are the pickup point for deliveries. */
@Embeddable
public class BusinessAddress {

    @Column(name = "address_line1", length = 200)
    private String line1;

    @Column(name = "address_line2", length = 200)
    private String line2;

    @Column(name = "address_city", length = 100)
    private String city;

    @Column(name = "address_state", length = 100)
    private String state;

    @Column(name = "address_postal_code", length = 10)
    private String postalCode;

    @Column(name = "address_country", length = 2)
    private String country;

    @Column(name = "address_latitude")
    private Double latitude;

    @Column(name = "address_longitude")
    private Double longitude;

    protected BusinessAddress() {
    }

    public BusinessAddress(String line1, String line2, String city, String state, String postalCode,
                           String country, Double latitude, Double longitude) {
        this.line1 = line1;
        this.line2 = line2;
        this.city = city;
        this.state = state;
        this.postalCode = postalCode;
        this.country = country;
        this.latitude = latitude;
        this.longitude = longitude;
    }

    public String getLine1() {
        return line1;
    }

    public String getLine2() {
        return line2;
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
}
