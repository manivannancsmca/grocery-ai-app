package com.grocery.vendor;

import com.grocery.common.id.Ids;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The public store directory (docs/api/phase-3-catalog-and-search.md §9): GET /api/stores/nearby and GET
 * /api/stores/{id}. Chennai: the customer is in Selaiyur; distances are haversine × 1.3, 1 decimal HALF_UP (the cart's
 * formula). The MySQL container is shared with the other test classes, so assertions look at this test's stores only.
 */
class StoreDirectoryIntegrationTest extends AbstractIntegrationTest {

    /** The customer's point: Selaiyur, Chennai. */
    private static final String LAT = "12.9202";
    private static final String LNG = "80.1445";

    private static final Set<String> CARD_FIELDS = Set.of("id", "name", "line1", "area", "city", "postalCode",
            "latitude", "longitude", "distanceKm", "deliversHere");

    private final UUID admin = UUID.randomUUID();

    @Autowired
    private JdbcTemplate jdbc;

    private static String profile(String name, String line1, String area, double latitude, double longitude) {
        return """
                {"businessName":"%s","legalName":"%s Pvt Ltd","gstin":"33ABCDE1234F1Z5","fssaiLicense":"12345678901234",
                 "contactEmail":"ops@store.example.in","contactPhone":"+919876543210",
                 "address":{"line1":"%s","line2":%s,"city":"Chennai","state":"Tamil Nadu","postalCode":"600045",
                            "country":"IN","latitude":%s,"longitude":%s}}"""
                .formatted(name, name, line1, area == null ? "null" : "\"" + area + "\"", latitude, longitude);
    }

    /** A vendor whose profile is saved (DRAFT); returns its id. */
    private String draft(UUID owner, String name, double latitude, double longitude) throws Exception {
        return json.readTree(mvc.perform(put("/api/vendors/me").with(as(owner, "VENDOR"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(profile(name, "1 Main Road", null, latitude, longitude)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).get("id").asString();
    }

    private void submit(UUID owner) throws Exception {
        mvc.perform(post("/api/vendors/me/submit").with(as(owner, "VENDOR"))).andExpect(status().isOk());
    }

    private void adminAction(String vendorId, String action) throws Exception {
        var request = post("/api/admin/vendors/{id}/" + action, vendorId).with(as(admin, "OPS_ADMIN"));
        if (action.equals("suspend")) {
            request.contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"Repeated late deliveries\"}");
        }
        mvc.perform(request).andExpect(status().isOk());
    }

    /** An APPROVED store; returns its id. */
    private String approvedStore(String name, String line1, String area, double latitude, double longitude)
            throws Exception {
        UUID owner = UUID.randomUUID();
        String id = json.readTree(mvc.perform(put("/api/vendors/me").with(as(owner, "VENDOR"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(profile(name, line1, area, latitude, longitude)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).get("id").asString();
        submit(owner);
        adminAction(id, "approve");
        return id;
    }

    private String approvedStore(String name, double latitude, double longitude) throws Exception {
        return approvedStore(name, "1 Main Road", null, latitude, longitude);
    }

    /** An approved store whose coordinates were lost (none can be approved without; older rows might). */
    private void removeLocation(String vendorId) {
        assertThat(jdbc.update("update vendor set address_latitude = null, address_longitude = null "
                + "where id = UUID_TO_BIN(?)", vendorId)).isEqualTo(1);
    }

    /** The property names of a JSON object. */
    @SuppressWarnings("unchecked")
    private Set<String> keys(String body) {
        return ((Map<String, Object>) json.readValue(body, Map.class)).keySet();
    }

    /** GET /api/stores/nearby as a guest (no token). */
    private JsonNode nearby(String lat, String lng) throws Exception {
        return json.readTree(mvc.perform(get("/api/stores/nearby").param("lat", lat).param("lng", lng))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    /** The ids of {@code response.content} that belong to {@code mine}, in the response's order. */
    private static List<String> idsOf(JsonNode response, Set<String> mine) {
        List<String> ids = new ArrayList<>();
        response.get("content").forEach(card -> {
            if (mine.contains(card.get("id").asString())) {
                ids.add(card.get("id").asString());
            }
        });
        return ids;
    }

    private static JsonNode cardOf(JsonNode response, String id) {
        for (JsonNode card : response.get("content")) {
            if (card.get("id").asString().equals(id)) {
                return card;
            }
        }
        throw new AssertionError("store " + id + " is not in the list");
    }

    private ResultActions store(String id, String... latLng) throws Exception {
        var request = get("/api/stores/{id}", id);
        if (latLng.length == 2) {
            request.param("lat", latLng[0]).param("lng", latLng[1]);
        }
        return mvc.perform(request);
    }

    @Test
    void theApprovedStoresWithinTheDeliveryLimitAreListedNearestFirst() throws Exception {
        String selaiyur = approvedStore("Selaiyur Fresh Mart", 12.9180, 80.1420);                  // 0.5 km
        String tambaram = approvedStore("Tambaram Super Bazaar", "112 Shanmugam Road", "West Tambaram",
                12.9267, 80.1146);                                                                    // 4.3 km
        String annai = approvedStore("Annai Stores", 12.9267, 80.1146);                            // 4.3 km, by name
        String north = approvedStore("Madambakkam Daily Needs", 13.0218, 80.1445);                 // 14.7 km
        String atTheLimit = approvedStore("Vandalur Provisions", 12.8166, 80.1445);                // 14.98 → 15.0 km
        String justBeyond = approvedStore("Kelambakkam Kirana", 12.8159, 80.1445);                 // 15.08 → 15.1 km
        String corner = approvedStore("Sholinganallur Supermarket", 13.0152, 80.2395);             // in the box, 19.2 km
        String annaNagar = approvedStore("Anna Nagar Grocers", 13.085, 80.215);                    // 25.8 km

        UUID draftOwner = UUID.randomUUID();
        String draft = draft(draftOwner, "Camp Road Draft Store", 12.9210, 80.1450);
        UUID pendingOwner = UUID.randomUUID();
        String pending = draft(pendingOwner, "Camp Road Pending Store", 12.9210, 80.1450);
        submit(pendingOwner);
        String suspended = approvedStore("Camp Road Suspended Store", 12.9210, 80.1450);
        adminAction(suspended, "suspend");
        String noLocation = approvedStore("Camp Road No Location Store", 12.9210, 80.1450);
        removeLocation(noLocation);

        JsonNode response = nearby(LAT, LNG);

        assertThat(response.get("deliveryLimitKm").asDouble()).isEqualTo(15.0);
        Set<String> mine = Set.of(selaiyur, tambaram, annai, north, atTheLimit, justBeyond, corner, annaNagar,
                draft, pending, suspended, noLocation);
        assertThat(idsOf(response, mine)).containsExactly(selaiyur, annai, tambaram, north, atTheLimit);

        JsonNode card = cardOf(response, tambaram);
        assertThat(card.get("name").asString()).isEqualTo("Tambaram Super Bazaar");
        assertThat(card.get("line1").asString()).isEqualTo("112 Shanmugam Road");
        assertThat(card.get("area").asString()).isEqualTo("West Tambaram");
        assertThat(card.get("city").asString()).isEqualTo("Chennai");
        assertThat(card.get("postalCode").asString()).isEqualTo("600045");
        assertThat(card.get("latitude").asDouble()).isEqualTo(12.9267);
        assertThat(card.get("longitude").asDouble()).isEqualTo(80.1146);
        assertThat(card.get("distanceKm").asDouble()).isEqualTo(4.3);
        assertThat(card.get("deliversHere").asBoolean()).isTrue();
        assertThat(cardOf(response, annai).get("area").isNull()).isTrue();
        assertThat(cardOf(response, selaiyur).get("distanceKm").asDouble()).isEqualTo(0.5);
        assertThat(cardOf(response, north).get("distanceKm").asDouble()).isEqualTo(14.7);
        assertThat(cardOf(response, atTheLimit).get("distanceKm").asDouble()).isEqualTo(15.0);

        // every listed store delivers here, nearest first, and shows nothing but the public card
        double previous = -1;
        for (JsonNode listed : response.get("content")) {
            assertThat(listed.get("deliversHere").asBoolean()).isTrue();
            assertThat(listed.get("distanceKm").asDouble()).isBetween(previous, 15.0);
            previous = listed.get("distanceKm").asDouble();
        }
        assertThat(keys(card.toString())).containsExactlyInAnyOrderElementsOf(CARD_FIELDS);
        String body = mvc.perform(get("/api/stores/nearby").param("lat", LAT).param("lng", LNG))
                .andReturn().getResponse().getContentAsString();
        assertThat(body).contains("\"deliveryLimitKm\":15.0").contains("\"distanceKm\":4.3")
                .doesNotContain("33ABCDE1234F1Z5", "12345678901234", "ops@store.example.in", "+919876543210",
                        "ownerUserId", "APPROVED", "SUSPENDED");
    }

    @Test
    void aStoreShowsItsDistanceOnlyWhenAPointIsGiven() throws Exception {
        String tambaram = approvedStore("Tambaram Super Bazaar", "112 Shanmugam Road", "West Tambaram",
                12.9267, 80.1146);
        String annaNagar = approvedStore("Anna Nagar Grocers", 13.085, 80.215);

        String body = store(tambaram)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(tambaram))
                .andExpect(jsonPath("$.name").value("Tambaram Super Bazaar"))
                .andExpect(jsonPath("$.line1").value("112 Shanmugam Road"))
                .andExpect(jsonPath("$.area").value("West Tambaram"))
                .andExpect(jsonPath("$.city").value("Chennai"))
                .andExpect(jsonPath("$.postalCode").value("600045"))
                .andExpect(jsonPath("$.latitude").value(12.9267))
                .andExpect(jsonPath("$.longitude").value(80.1146))
                .andReturn().getResponse().getContentAsString();
        JsonNode withoutPoint = json.readTree(body);
        assertThat(withoutPoint.get("distanceKm").isNull()).isTrue();
        assertThat(withoutPoint.get("deliversHere").isNull()).isTrue();
        assertThat(keys(body)).containsExactlyInAnyOrderElementsOf(CARD_FIELDS);

        store(tambaram, LAT, LNG)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.distanceKm").value(4.3))
                .andExpect(jsonPath("$.deliversHere").value(true));
        // far away: still answered, with the distance, but it does not deliver there
        store(annaNagar, LAT, LNG)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Anna Nagar Grocers"))
                .andExpect(jsonPath("$.distanceKm").value(25.8))
                .andExpect(jsonPath("$.deliversHere").value(false));
        // a valid token is accepted too
        mvc.perform(get("/api/stores/{id}", tambaram).with(as(UUID.randomUUID(), "CUSTOMER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Tambaram Super Bazaar"));
        mvc.perform(get("/api/stores/nearby").param("lat", LAT).param("lng", LNG)
                        .with(as(UUID.randomUUID(), "CUSTOMER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray());
    }

    @Test
    void aStoreWithoutALocationHasNoDistanceAndDeliversNowhere() throws Exception {
        String noLocation = approvedStore("Camp Road No Location Store", 12.9210, 80.1450);
        removeLocation(noLocation);

        JsonNode card = json.readTree(store(noLocation, LAT, LNG)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deliversHere").value(false))
                .andReturn().getResponse().getContentAsString());
        assertThat(card.get("distanceKm").isNull()).isTrue();
        assertThat(card.get("latitude").isNull()).isTrue();
    }

    @Test
    void onlyApprovedStoresAreFound() throws Exception {
        UUID draftOwner = UUID.randomUUID();
        String draft = draft(draftOwner, "Camp Road Draft Store", 12.9210, 80.1450);
        UUID pendingOwner = UUID.randomUUID();
        String pending = draft(pendingOwner, "Camp Road Pending Store", 12.9210, 80.1450);
        submit(pendingOwner);
        String suspended = approvedStore("Camp Road Suspended Store", 12.9210, 80.1450);
        store(suspended).andExpect(status().isOk());
        adminAction(suspended, "suspend");

        for (String id : List.of(draft, pending, suspended, Ids.newId().toString())) {
            store(id)
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.errorCode").value("STORE_NOT_FOUND"));
            store(id, LAT, LNG)
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.errorCode").value("STORE_NOT_FOUND"));
        }

        adminAction(suspended, "reinstate");
        store(suspended).andExpect(status().isOk()).andExpect(jsonPath("$.name").value("Camp Road Suspended Store"));
        assertThat(idsOf(nearby(LAT, LNG), Set.of(suspended))).containsExactly(suspended);
    }

    @Test
    void aMissingOrInvalidPointIsRejected() throws Exception {
        String tambaram = approvedStore("Tambaram Super Bazaar", 12.9267, 80.1146);

        List<Map<String, String>> invalidNearby = List.of(
                Map.of(),
                Map.of("lat", LAT),
                Map.of("lng", LNG),
                Map.of("lat", "", "lng", LNG),
                Map.of("lat", "north", "lng", LNG),
                Map.of("lat", LAT, "lng", "east"),
                Map.of("lat", "90.0001", "lng", LNG),
                Map.of("lat", "-91", "lng", LNG),
                Map.of("lat", LAT, "lng", "180.5"),
                Map.of("lat", LAT, "lng", "-181"),
                Map.of("lat", "NaN", "lng", LNG));
        for (Map<String, String> params : invalidNearby) {
            var request = get("/api/stores/nearby");
            params.forEach(request::param);
            mvc.perform(request)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errorCode").value("INVALID_PARAMETER"));
        }

        List<Map<String, String>> invalidStore = List.of(
                Map.of("lat", LAT),
                Map.of("lng", LNG),
                Map.of("lat", "95", "lng", LNG),
                Map.of("lat", LAT, "lng", "200"),
                Map.of("lat", "north", "lng", LNG));
        for (Map<String, String> params : invalidStore) {
            var request = get("/api/stores/{id}", tambaram);
            params.forEach(request::param);
            mvc.perform(request)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errorCode").value("INVALID_PARAMETER"));
        }
        mvc.perform(get("/api/stores/{id}", "not-a-uuid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_PARAMETER"));

        // the edges of the range are valid
        mvc.perform(get("/api/stores/nearby").param("lat", "-90").param("lng", "180")).andExpect(status().isOk());
        mvc.perform(get("/api/stores/nearby").param("lat", "90").param("lng", "-180")).andExpect(status().isOk());
    }
}
