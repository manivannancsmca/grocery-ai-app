package com.grocery.delivery.dispatch.geo;

/**
 * Straight-line distances and the ETA rule of contract §2: haversine in km; ETA = distance × 1.3 / 20 km/h, rounded
 * up to whole minutes.
 */
public final class Distances {

    /** Mean Earth radius, km. */
    static final double EARTH_RADIUS_KM = 6371.0;
    /** Road distance ≈ straight line × 1.3. */
    static final double DETOUR_FACTOR = 1.3;
    static final double SPEED_KMH = 20.0;

    private Distances() {
    }

    public static double haversineKm(GeoPoint from, GeoPoint to) {
        double lat1 = Math.toRadians(from.latitude());
        double lat2 = Math.toRadians(to.latitude());
        double dLat = lat2 - lat1;
        double dLng = Math.toRadians(to.longitude() - from.longitude());
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(lat1) * Math.cos(lat2) * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        return 2 * EARTH_RADIUS_KM * Math.asin(Math.min(1.0, Math.sqrt(a)));
    }

    /** The distance between two points, or null when either is unknown. */
    public static Double haversineKmOrNull(GeoPoint from, GeoPoint to) {
        return from == null || to == null ? null : haversineKm(from, to);
    }

    /** Minutes for {@code km} of straight line at 20 km/h with the 1.3 detour factor, rounded up. */
    public static int etaMinutes(double km) {
        double minutes = km * DETOUR_FACTOR / SPEED_KMH * 60.0;
        return (int) Math.ceil(minutes - 1e-9);
    }

    /** A distance as shown in views and events: km with one decimal. */
    public static double roundKm(double km) {
        return Math.round(km * 10.0) / 10.0;
    }

    public static Double roundKm(Double km) {
        return km == null ? null : roundKm(km.doubleValue());
    }
}
