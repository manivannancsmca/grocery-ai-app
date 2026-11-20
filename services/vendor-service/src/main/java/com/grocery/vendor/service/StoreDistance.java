package com.grocery.vendor.service;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Distances of the store directory. {@link #roadKm} is exactly cart-service's {@code DeliveryCalculator} (haversine
 * with earth radius 6371.0 km × road factor, 1 decimal HALF_UP, store first), so "delivers here" in the directory and
 * "deliverable" at checkout always agree.
 */
final class StoreDistance {

    static final double EARTH_RADIUS_KM = 6371.0;

    /** A road distance below {@code limit + 0.05} still rounds (HALF_UP, 1 decimal) to at most {@code limit}. */
    private static final BigDecimal ROUNDING_HEADROOM_KM = new BigDecimal("0.05");
    /** The bounding box is a little larger than needed: it only prefilters, the exact distance decides. */
    private static final double BOX_MARGIN = 1.01;

    private StoreDistance() {
    }

    /** Road distance from the store to the point in km: haversine × {@code roadFactor}, 1 decimal HALF_UP. */
    static BigDecimal roadKm(double storeLatitude, double storeLongitude, double latitude, double longitude,
                             BigDecimal roadFactor) {
        return BigDecimal.valueOf(haversineKm(storeLatitude, storeLongitude, latitude, longitude))
                .multiply(roadFactor)
                .setScale(1, RoundingMode.HALF_UP);
    }

    /** Great-circle distance in km (the cart's formula, operand for operand). */
    static double haversineKm(double fromLatitude, double fromLongitude, double toLatitude, double toLongitude) {
        double lat1 = Math.toRadians(fromLatitude);
        double lat2 = Math.toRadians(toLatitude);
        double deltaLat = lat2 - lat1;
        double deltaLng = Math.toRadians(toLongitude - fromLongitude);
        double a = Math.pow(Math.sin(deltaLat / 2), 2)
                + Math.cos(lat1) * Math.cos(lat2) * Math.pow(Math.sin(deltaLng / 2), 2);
        return 2 * EARTH_RADIUS_KM * Math.asin(Math.min(1.0, Math.sqrt(a)));
    }

    /**
     * The box around the point that holds every store whose rounded road distance can be within the limit: straight-line
     * radius {@code (deliveryLimitKm + 0.05) / roadFactor}, plus a 1 % margin.
     */
    static BoundingBox searchBox(double latitude, double longitude, BigDecimal deliveryLimitKm, BigDecimal roadFactor) {
        double radiusKm = deliveryLimitKm.add(ROUNDING_HEADROOM_KM).doubleValue() / roadFactor.doubleValue() * BOX_MARGIN;
        return BoundingBox.around(latitude, longitude, radiusKm);
    }

    /** Latitude / longitude ranges in degrees, bounds inclusive. */
    record BoundingBox(double minLatitude, double maxLatitude, double minLongitude, double maxLongitude) {

        /**
         * Every point within {@code radiusKm} (great circle) of the centre. Latitude ± the angular radius; longitude
         * ± asin(sin r / cos lat). When a pole lies within the radius, or the range crosses the ±180° meridian, the box
         * spans every longitude (the exact distance still filters).
         */
        static BoundingBox around(double latitude, double longitude, double radiusKm) {
            double angular = radiusKm / EARTH_RADIUS_KM;
            double minLat = latitude - Math.toDegrees(angular);
            double maxLat = latitude + Math.toDegrees(angular);
            if (minLat <= -90 || maxLat >= 90) {
                return new BoundingBox(Math.max(minLat, -90), Math.min(maxLat, 90), -180, 180);
            }
            double deltaLng = Math.toDegrees(Math.asin(Math.sin(angular) / Math.cos(Math.toRadians(latitude))));
            double minLng = longitude - deltaLng;
            double maxLng = longitude + deltaLng;
            if (minLng < -180 || maxLng > 180) {
                return new BoundingBox(minLat, maxLat, -180, 180);
            }
            return new BoundingBox(minLat, maxLat, minLng, maxLng);
        }
    }
}
