package com.grocery.delivery.dispatch.geo;

/** A WGS84 position in degrees. */
public record GeoPoint(double latitude, double longitude) {

    /** The point, or null when either coordinate is unknown. */
    public static GeoPoint of(Double latitude, Double longitude) {
        return latitude == null || longitude == null ? null : new GeoPoint(latitude, longitude);
    }
}
