package com.grocery.vendor.service;

import com.grocery.vendor.service.StoreDistance.BoundingBox;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/** The directory's distance (the cart's formula) and the bounding box that prefilters the stores. */
class StoreDistanceTest {

    private static final BigDecimal ROAD_FACTOR = new BigDecimal("1.3");
    private static final BigDecimal LIMIT = new BigDecimal("15");

    @Test
    void theRoadDistanceIsHaversineTimesTheRoadFactorRoundedHalfUp() {
        // Selaiyur (customer) ← Tambaram store: 3.3201 km straight, 4.316 km by road
        assertThat(StoreDistance.roadKm(12.9267, 80.1146, 12.9202, 80.1445, ROAD_FACTOR))
                .isEqualByComparingTo("4.3");
        // ← Anna Nagar: 19.853 km straight, 25.809 km by road
        assertThat(StoreDistance.roadKm(13.085, 80.215, 12.9202, 80.1445, ROAD_FACTOR))
                .isEqualByComparingTo("25.8");
        assertThat(StoreDistance.roadKm(12.9202, 80.1445, 12.9202, 80.1445, ROAD_FACTOR))
                .isEqualByComparingTo("0.0");
        assertThat(StoreDistance.roadKm(12.9202, 80.1445, 12.9202, 80.1445, ROAD_FACTOR).scale()).isEqualTo(1);
    }

    @Test
    void theBoxHoldsEveryPointThatCanStillRoundToTheLimit() {
        // a road distance just below 15.05 rounds to 15.0: straight-line 15.05 / 1.3 km in every direction
        double radiusKm = 15.05 / 1.3;
        for (double[] centre : new double[][]{{12.9202, 80.1445}, {0, 0}, {-33.87, 151.21}, {60.17, 24.94},
                {78.22, 15.65}, {-77.85, 166.67}}) {
            BoundingBox box = StoreDistance.searchBox(centre[0], centre[1], LIMIT, ROAD_FACTOR);
            for (int bearing = 0; bearing < 360; bearing += 5) {
                double[] point = destination(centre[0], centre[1], bearing, radiusKm);
                assertThat(StoreDistance.haversineKm(point[0], point[1], centre[0], centre[1]))
                        .isCloseTo(radiusKm, org.assertj.core.data.Offset.offset(1e-6));
                assertThat(point[0]).as("latitude at %s° from %s,%s", bearing, centre[0], centre[1])
                        .isBetween(box.minLatitude(), box.maxLatitude());
                assertThat(point[1]).as("longitude at %s° from %s,%s", bearing, centre[0], centre[1])
                        .isBetween(box.minLongitude(), box.maxLongitude());
            }
        }
    }

    @Test
    void theBoxIsSmallAroundAChennaiPoint() {
        BoundingBox box = StoreDistance.searchBox(12.9202, 80.1445, LIMIT, ROAD_FACTOR);
        // 15.05 / 1.3 km × 1.01 = 11.69 km ≈ 0.105° of latitude, 0.108° of longitude at 12.9° N
        assertThat(box.maxLatitude() - 12.9202).isBetween(0.104, 0.106);
        assertThat(box.maxLongitude() - 80.1445).isBetween(0.107, 0.109);
        assertThat(12.9202 - box.minLatitude()).isEqualTo(box.maxLatitude() - 12.9202,
                org.assertj.core.data.Offset.offset(1e-9));
    }

    @Test
    void nearAPoleOrTheDateLineTheBoxSpansEveryLongitude() {
        BoundingBox pole = StoreDistance.searchBox(89.95, 10, LIMIT, ROAD_FACTOR);
        assertThat(pole.maxLatitude()).isEqualTo(90);
        assertThat(pole.minLongitude()).isEqualTo(-180);
        assertThat(pole.maxLongitude()).isEqualTo(180);

        BoundingBox dateLine = StoreDistance.searchBox(-17.7, 179.98, LIMIT, ROAD_FACTOR);
        assertThat(dateLine.minLongitude()).isEqualTo(-180);
        assertThat(dateLine.maxLongitude()).isEqualTo(180);
        assertThat(dateLine.maxLatitude()).isLessThan(-17.5);
    }

    /** The point {@code km} away from the start on the initial {@code bearing} (great circle), longitude in ±180. */
    private static double[] destination(double latitude, double longitude, double bearing, double km) {
        double angular = km / StoreDistance.EARTH_RADIUS_KM;
        double lat1 = Math.toRadians(latitude);
        double theta = Math.toRadians(bearing);
        double lat2 = Math.asin(Math.sin(lat1) * Math.cos(angular)
                + Math.cos(lat1) * Math.sin(angular) * Math.cos(theta));
        double lng2 = Math.toRadians(longitude) + Math.atan2(Math.sin(theta) * Math.sin(angular) * Math.cos(lat1),
                Math.cos(angular) - Math.sin(lat1) * Math.sin(lat2));
        double lngDegrees = (Math.toDegrees(lng2) + 540) % 360 - 180;
        return new double[]{Math.toDegrees(lat2), lngDegrees};
    }
}
