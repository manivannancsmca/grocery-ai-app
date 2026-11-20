package com.grocery.catalogquery;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.time.DayOfWeek;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.grocery.catalogquery.TestEvents.hours;
import static com.grocery.catalogquery.TestEvents.item;
import static com.grocery.catalogquery.TestEvents.menuCategory;
import static com.grocery.catalogquery.TestEvents.restaurant;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class RestaurantSearchIntegrationTest extends AbstractIntegrationTest {

    /** MG Road, Bengaluru; 0.009° of latitude ≈ 1 km. */
    private static final double LAT = 12.9756;
    private static final double LNG = 77.6066;
    private static final ZoneId INDIA = ZoneId.of("Asia/Kolkata");

    private final String spiceRoute = id();     // ~2 km, biryani
    private final String greenLeaf = id();      // ~5 km, pure veg
    private final String farAway = id();        // ~25 km, biryani
    private final String noLocation = id();

    private void publish(String restaurantId, Map<String, Object> listing) {
        publishRestaurantEvent("RestaurantListingUpdated", restaurantId, listing);
    }

    private void publishNeighbourhood() throws Exception {
        Map<String, Object> a = restaurant(spiceRoute, "Spice Route", 1, LAT + 0.018, LNG,
                List.of(item("Chicken Biryani", "249.00", "NON_VEG"), item("Paneer Tikka", "199.00", "VEG")));
        a.put("cuisines", List.of("NORTH_INDIAN", "MUGHLAI"));
        publish(spiceRoute, a);
        Map<String, Object> b = restaurant(greenLeaf, "Green Leaf", 1, LAT + 0.045, LNG,
                List.of(item("Masala Dosa", "120.00", "VEG"), item("Veg Thali", "180.00", "VEG")));
        b.put("cuisines", List.of("SOUTH_INDIAN"));
        publish(greenLeaf, b);
        Map<String, Object> c = restaurant(farAway, "Highway Dhaba", 1, LAT + 0.225, LNG,
                List.of(item("Mutton Biryani", "320.00", "NON_VEG"), item("Egg Curry", "150.00", "EGG")));
        c.put("cuisines", List.of("NORTH_INDIAN"));
        publish(farAway, c);
        publish(noLocation, restaurant(noLocation, "Cloud Kitchen", 1, null, null,
                List.of(item("Butter Chicken", "280.00", "NON_VEG"))));
        refresh();
    }

    private JsonNode search(String... params) throws Exception {
        var request = get("/api/search/restaurants");
        for (int i = 0; i < params.length; i += 2) {
            request = request.param(params[i], params[i + 1]);
        }
        return body(mvc.perform(request).andExpect(status().isOk()));
    }

    private static List<String> ids(JsonNode response) {
        List<String> ids = new ArrayList<>();
        response.get("content").forEach(card -> ids.add(card.get("id").asString()));
        return ids;
    }

    private static String lat() {
        return String.valueOf(LAT);
    }

    private static String lng() {
        return String.valueOf(LNG);
    }

    @Test
    void nearbyRestaurantsAreFilteredByRadiusAndSortedByDistance() throws Exception {
        publishNeighbourhood();

        JsonNode nearby = search("lat", lat(), "lng", lng());
        assertThat(ids(nearby)).containsExactly(spiceRoute, greenLeaf);
        JsonNode first = nearby.get("content").get(0);
        assertThat(first.get("name").asString()).isEqualTo("Spice Route");
        assertThat(first.get("distanceKm").asDouble()).isEqualTo(2.0);
        assertThat(first.get("city").asString()).isEqualTo("Bengaluru");
        assertThat(first.get("locality").asString()).isEqualTo("Indiranagar");
        assertThat(first.get("acceptingOrders").asBoolean()).isTrue();
        assertThat(first.get("pureVeg").asBoolean()).isFalse();
        assertThat(first.get("cuisines").get(0).asString()).isEqualTo("NORTH_INDIAN");
        assertThat(first.get("matchingItems")).isEmpty();
        assertThat(nearby.get("content").get(1).get("distanceKm").asDouble()).isEqualTo(5.0);

        assertThat(ids(search("lat", lat(), "lng", lng(), "radiusKm", "30")))
                .containsExactly(spiceRoute, greenLeaf, farAway);
        assertThat(ids(search("lat", lat(), "lng", lng(), "radiusKm", "3"))).containsExactly(spiceRoute);
        // radius is capped at 30 km
        assertThat(ids(search("lat", lat(), "lng", lng(), "radiusKm", "500")))
                .containsExactly(spiceRoute, greenLeaf, farAway);

        // without a location: everything, by name, no distance
        JsonNode all = search();
        assertThat(all.get("totalElements").asLong()).isEqualTo(4);
        assertThat(all.get("content").get(0).get("name").asString()).isEqualTo("Cloud Kitchen");
        assertThat(all.get("content").get(0).get("distanceKm").isNull()).isTrue();
    }

    @Test
    void dishSearchFindsRestaurantsAndReturnsTheMatchingItems() throws Exception {
        publishNeighbourhood();

        JsonNode biryani = search("q", "biryani", "lat", lat(), "lng", lng(), "radiusKm", "30");
        assertThat(ids(biryani)).containsExactlyInAnyOrder(spiceRoute, farAway);
        JsonNode spice = cardOf(biryani, spiceRoute);
        assertThat(spice.get("matchingItems")).hasSize(1);
        assertThat(spice.get("matchingItems").get(0).get("name").asString()).isEqualTo("Chicken Biryani");
        assertThat(spice.get("matchingItems").get(0).get("price").asDouble()).isEqualTo(249.0);
        assertThat(spice.get("matchingItems").get(0).get("foodType").asString()).isEqualTo("NON_VEG");
        assertThat(spice.get("matchingItems").get(0).get("id").asString()).isNotBlank();
        assertThat(spice.get("distanceKm").asDouble()).isEqualTo(2.0);

        // typo in the dish name; no location
        JsonNode typo = search("q", "biriyani");
        assertThat(ids(typo)).containsExactlyInAnyOrder(spiceRoute, farAway);
        assertThat(cardOf(typo, farAway).get("matchingItems").get(0).get("name").asString())
                .isEqualTo("Mutton Biryani");

        // restaurant names and cuisines match too
        assertThat(ids(search("q", "green leaf"))).containsExactly(greenLeaf);
        assertThat(ids(search("q", "south indian"))).containsExactly(greenLeaf);
        assertThat(cardOf(search("q", "south indian"), greenLeaf).get("matchingItems")).isEmpty();
    }

    @Test
    void cuisineAndPureVegFiltersWithCuisineFacets() throws Exception {
        publishNeighbourhood();

        assertThat(ids(search("pureVeg", "true"))).containsExactly(greenLeaf);
        assertThat(ids(search("cuisine", "NORTH_INDIAN"))).containsExactlyInAnyOrder(spiceRoute, farAway, noLocation);
        assertThat(ids(search("cuisine", "mughlai", "cuisine", "SOUTH_INDIAN")))
                .containsExactlyInAnyOrder(spiceRoute, greenLeaf);

        JsonNode facets = search().get("facets").get("cuisines");
        assertThat(facets.get(0).get("value").asString()).isEqualTo("NORTH_INDIAN");
        assertThat(facets.get(0).get("count").asLong()).isEqualTo(3);
        assertThat(facets).hasSize(3);
    }

    @Test
    void openNowUsesIndiaTimeAndOvernightHours() throws Exception {
        String nightOwl = id();
        Map<String, Object> night = restaurant(nightOwl, "Night Owl", 1, LAT, LNG,
                List.of(item("Maggi", "60.00", "VEG")));
        night.put("openingHours", List.of(hours("FRIDAY", "18:00", "02:00")));
        publish(nightOwl, night);
        String dayCafe = id();
        publish(dayCafe, restaurant(dayCafe, "Day Cafe", 1, LAT, LNG, List.of(item("Filter Coffee", "40.00", "VEG"))));
        refresh();

        ZonedDateTime fridayNight = ZonedDateTime.of(2026, 11, 13, 23, 30, 0, 0, INDIA);
        assertThat(fridayNight.getDayOfWeek()).isEqualTo(DayOfWeek.FRIDAY);

        clock.set(fridayNight.toInstant());
        assertThat(ids(search("openNow", "true"))).containsExactly(nightOwl);
        assertThat(cardOf(search(), dayCafe).get("openNow").asBoolean()).isFalse();
        assertThat(cardOf(search(), nightOwl).get("openNow").asBoolean()).isTrue();

        clock.set(fridayNight.plusHours(2).toInstant());           // Saturday 01:30, still Friday's shift
        assertThat(ids(search("openNow", "true"))).containsExactly(nightOwl);
        mvc.perform(get("/api/catalog/restaurants/{id}", nightOwl))
                .andExpect(jsonPath("$.openNow").value(true))
                .andExpect(jsonPath("$.openingHours.length()").value(1))
                .andExpect(jsonPath("$.openingHours[0].dayOfWeek").value("FRIDAY"))
                .andExpect(jsonPath("$.openingHours[0].opensAt").value("18:00"))
                .andExpect(jsonPath("$.openingHours[0].closesAt").value("02:00"));

        clock.set(fridayNight.plusHours(3).plusMinutes(30).toInstant());   // Saturday 03:00
        assertThat(ids(search("openNow", "true"))).isEmpty();
        mvc.perform(get("/api/catalog/restaurants/{id}", nightOwl)).andExpect(jsonPath("$.openNow").value(false));

        clock.set(fridayNight.plusHours(10).plusMinutes(30).toInstant());  // Saturday 10:00
        assertThat(ids(search("openNow", "true"))).containsExactly(dayCafe);
    }

    @Test
    void suspendedRestaurantsAreHiddenAndVersionsAreRespected() throws Exception {
        publishNeighbourhood();

        Map<String, Object> suspended = restaurant(spiceRoute, "Spice Route", 3, LAT + 0.018, LNG,
                List.of(item("Chicken Biryani", "249.00", "NON_VEG")));
        suspended.put("status", "SUSPENDED");
        publish(spiceRoute, suspended);
        // an older approved snapshot arriving late must not bring it back
        publish(spiceRoute, restaurant(spiceRoute, "Spice Route", 2, LAT + 0.018, LNG,
                List.of(item("Chicken Biryani", "249.00", "NON_VEG"))));
        refresh();

        assertThat(ids(search("q", "biryani"))).containsExactly(farAway);
        assertThat(ids(search("lat", lat(), "lng", lng()))).containsExactly(greenLeaf);
        mvc.perform(get("/api/catalog/restaurants/{id}", spiceRoute))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("RESTAURANT_NOT_FOUND"));

        Map<String, Object> reinstated = restaurant(spiceRoute, "Spice Route", 4, LAT + 0.018, LNG,
                List.of(item("Chicken Biryani", "249.00", "NON_VEG")));
        reinstated.put("acceptingOrders", false);
        publish(spiceRoute, reinstated);
        refresh();
        JsonNode card = cardOf(search("q", "biryani"), spiceRoute);
        assertThat(card.get("acceptingOrders").asBoolean()).isFalse();
    }

    @Test
    void restaurantPageShowsTheMenuWithoutEmptyCategories() throws Exception {
        String restaurantId = id();
        Map<String, Object> listing = restaurant(restaurantId, "Spice Route", 1, LAT, LNG, List.of());
        Map<String, Object> soldOut = item("Mutton Biryani", "320.00", "NON_VEG");
        soldOut.put("available", false);
        Map<String, Object> second = item("Veg Biryani", "199.00", "VEG");
        second.put("sortOrder", 2);
        Map<String, Object> firstItem = item("Chicken Biryani", "249.00", "NON_VEG");
        firstItem.put("sortOrder", 1);
        listing.put("menu", List.of(
                menuCategory("Desserts", 2, List.of()),
                menuCategory("Biryani", 1, List.of(second, soldOut, firstItem))));
        publish(restaurantId, listing);
        refresh();

        mvc.perform(get("/api/catalog/restaurants/{id}", restaurantId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(restaurantId))
                .andExpect(jsonPath("$.name").value("Spice Route"))
                .andExpect(jsonPath("$.cuisines[0]").value("NORTH_INDIAN"))
                .andExpect(jsonPath("$.acceptingOrders").value(true))
                .andExpect(jsonPath("$.pureVeg").value(false))
                .andExpect(jsonPath("$.address.line1").value("12 MG Road"))
                .andExpect(jsonPath("$.address.line2").value("Indiranagar"))
                .andExpect(jsonPath("$.address.city").value("Bengaluru"))
                .andExpect(jsonPath("$.address.state").value("Karnataka"))
                .andExpect(jsonPath("$.address.postalCode").value("560038"))
                .andExpect(jsonPath("$.address.latitude").value(LAT))
                .andExpect(jsonPath("$.address.longitude").value(LNG))
                .andExpect(jsonPath("$.openingHours.length()").value(7))
                .andExpect(jsonPath("$.menu.length()").value(1))
                .andExpect(jsonPath("$.menu[0].name").value("Biryani"))
                .andExpect(jsonPath("$.menu[0].items.length()").value(3))
                .andExpect(jsonPath("$.menu[0].items[0].name").value("Mutton Biryani"))
                .andExpect(jsonPath("$.menu[0].items[0].available").value(false))
                .andExpect(jsonPath("$.menu[0].items[1].name").value("Chicken Biryani"))
                .andExpect(jsonPath("$.menu[0].items[1].price").value(249.0))
                .andExpect(jsonPath("$.menu[0].items[1].description").value("Chicken Biryani, freshly made"))
                .andExpect(jsonPath("$.menu[0].items[2].name").value("Veg Biryani"));
    }

    @Test
    void halfALocationOrABadNumberIsRejected() throws Exception {
        mvc.perform(get("/api/search/restaurants").param("lat", lat()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_PARAMETER"));
        mvc.perform(get("/api/search/restaurants").param("lat", "abc").param("lng", lng()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_PARAMETER"));
        mvc.perform(get("/api/search/restaurants").param("lat", "95").param("lng", lng()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_PARAMETER"));
    }

    private static JsonNode cardOf(JsonNode response, String restaurantId) {
        for (JsonNode card : response.get("content")) {
            if (card.get("id").asString().equals(restaurantId)) {
                return card;
            }
        }
        throw new AssertionError("Restaurant " + restaurantId + " is not in " + response);
    }
}
