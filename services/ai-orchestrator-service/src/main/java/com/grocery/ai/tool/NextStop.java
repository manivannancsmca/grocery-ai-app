package com.grocery.ai.tool;

import com.grocery.ai.support.Json;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;

/**
 * Where a delivery partner goes next with their active delivery, and how far that is: the pickup until the order is
 * picked up, then the customer. The distance is a straight line (haversine, like delivery-service measures it) from
 * the partner's last reported position, rounded to 0.1 km; null when either position is unknown. Used by the
 * {@code getActiveDelivery} tool and by the chat's live context, so both say the same — and no coordinates reach the
 * model.
 *
 * @param type       {@code PICKUP} or {@code CUSTOMER}
 * @param place      the pickup's name, or the customer's area ("Thiruvanmiyur, Chennai")
 * @param distanceKm straight-line distance in km, or null
 */
public record NextStop(String type, String place, Double distanceKm) {

    private static final double EARTH_RADIUS_KM = 6371.0088;

    /**
     * @param delivery        a DeliveryView ({@code /api/delivery/deliveries/active})
     * @param partnerLocation the {@code location} of the partner's own status, or null
     */
    public static NextStop of(JsonNode delivery, JsonNode partnerLocation) {
        boolean pickedUp = "PICKED_UP".equals(Json.text(delivery, "status"));
        JsonNode target = Json.at(delivery, pickedUp ? "drop" : "pickup");
        String place = pickedUp ? area(target) : Json.text(target, "name");
        double[] from = point(partnerLocation);
        double[] to = point(target);
        Double distance = from == null || to == null ? null : Math.round(distanceKm(from, to) * 10) / 10.0;
        return new NextStop(pickedUp ? "CUSTOMER" : "PICKUP", place, distance);
    }

    /** "Thiruvanmiyur, Chennai": the drop's locality, not the street. */
    private static String area(JsonNode drop) {
        String line2 = Json.text(drop, "line2");
        String city = Json.text(drop, "city");
        if (line2 == null || line2.isBlank()) {
            return city;
        }
        return city == null ? line2 : line2 + ", " + city;
    }

    /** {latitude, longitude} of a JSON object that has both, else null. */
    private static double[] point(JsonNode node) {
        if (node == null || !node.isObject()) {
            return null;
        }
        BigDecimal latitude = Json.decimal(node, "latitude");
        BigDecimal longitude = Json.decimal(node, "longitude");
        return latitude == null || longitude == null ? null : new double[] {latitude.doubleValue(), longitude.doubleValue()};
    }

    /** Straight-line (haversine) distance in km. */
    static double distanceKm(double[] from, double[] to) {
        double dLat = Math.toRadians(to[0] - from[0]);
        double dLon = Math.toRadians(to[1] - from[1]);
        double a = Math.pow(Math.sin(dLat / 2), 2) + Math.cos(Math.toRadians(from[0])) * Math.cos(Math.toRadians(to[0]))
                * Math.pow(Math.sin(dLon / 2), 2);
        return 2 * EARTH_RADIUS_KM * Math.asin(Math.min(1, Math.sqrt(a)));
    }

    /** "the pickup at Adyar Chettinad Kitchen, 0.6 km away in a straight line from your last reported position". */
    public String describe() {
        String where = "PICKUP".equals(type) ? "the pickup at " + (place == null ? "the store" : place)
                : "the customer" + (place == null ? "" : " in " + place);
        return distanceKm == null ? where
                : where + ", " + distanceKm + " km away in a straight line from your last reported position";
    }
}
