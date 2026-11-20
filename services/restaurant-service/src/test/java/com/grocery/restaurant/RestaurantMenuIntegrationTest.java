package com.grocery.restaurant;

import com.grocery.common.security.CurrentUser;
import com.grocery.restaurant.dto.MenuDtos.MenuItemRequest;
import com.grocery.restaurant.service.RestaurantMenuService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Phase 3: menus, the accepting-orders switch and the RestaurantListingUpdated snapshots (outbox rows). */
class RestaurantMenuIntegrationTest extends AbstractIntegrationTest {

    private static final String OWNER = "RESTAURANT_OWNER";
    private static final String LISTING = "RestaurantListingUpdated";
    private static final String COMPLETE_PROFILE = """
            {"name":"Spice Route","description":"North Indian kitchen","cuisines":["NORTH_INDIAN","BIRYANI"],
             "fssaiLicense":"12345678901234","contactEmail":"ops@spiceroute.in","contactPhone":"+919876543210",
             "address":{"line1":"5 Church Street","city":"Bengaluru","state":"Karnataka","postalCode":"560001",
                        "country":"IN","latitude":12.9752,"longitude":77.6050},
             "openingHours":[{"dayOfWeek":"SUNDAY","opensAt":"18:00","closesAt":"02:00"},
                             {"dayOfWeek":"MONDAY","opensAt":"09:00","closesAt":"23:00"}]}""";

    @Autowired
    DataSource dataSource;
    @Autowired
    RestaurantMenuService menuService;

    private final UUID admin = UUID.randomUUID();

    // ---------------------------------------------------------------- helpers

    private JdbcClient jdbc() {
        return JdbcClient.create(dataSource);
    }

    private String createProfile(UUID owner) throws Exception {
        return json.readTree(mvc.perform(put("/api/restaurants/me").with(as(owner, OWNER))
                        .contentType(MediaType.APPLICATION_JSON).content(COMPLETE_PROFILE))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).get("id").asString();
    }

    private void approve(UUID owner, String restaurantId) throws Exception {
        mvc.perform(post("/api/restaurants/me/submit").with(as(owner, OWNER))).andExpect(status().isOk());
        mvc.perform(post("/api/admin/restaurants/{id}/approve", restaurantId).with(as(admin, "OPS_ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"));
    }

    private ResultActions send(UUID owner, String method, String body, String path, Object... vars) throws Exception {
        var request = switch (method) {
            case "POST" -> post(path, vars);
            case "PUT" -> put(path, vars);
            case "PATCH" -> patch(path, vars);
            default -> throw new IllegalArgumentException(method);
        };
        return mvc.perform(request.with(as(owner, OWNER)).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private static String category(String name, int sortOrder) {
        return "{\"name\":\"%s\",\"description\":\"Chef's picks\",\"sortOrder\":%d}".formatted(name, sortOrder);
    }

    private static String item(String categoryId, String name, String price, String foodType, int sortOrder) {
        return """
                {"categoryId":"%s","name":"%s","description":"House special","price":%s,"foodType":"%s",
                 "sortOrder":%d}""".formatted(categoryId, name, price, foodType, sortOrder);
    }

    private String createCategory(UUID owner, String name, int sortOrder) throws Exception {
        return idOf(send(owner, "POST", category(name, sortOrder), "/api/restaurant/menu/categories")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value(name))
                .andExpect(jsonPath("$.items").isEmpty()));
    }

    private String createItem(UUID owner, String categoryId, String name, String price, String foodType,
                              int sortOrder) throws Exception {
        return idOf(send(owner, "POST", item(categoryId, name, price, foodType, sortOrder), "/api/restaurant/menu/items")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.categoryId").value(categoryId))
                .andExpect(jsonPath("$.available").value(true)));
    }

    private String idOf(ResultActions result) throws Exception {
        return json.readTree(result.andReturn().getResponse().getContentAsString()).get("id").asString();
    }

    private List<String> eventTypes(String restaurantId) {
        return jdbc().sql("SELECT event_type FROM outbox_event WHERE aggregate_id = ? ORDER BY occurred_at, id")
                .param(restaurantId).query(String.class).list();
    }

    /** Raw RestaurantListingUpdated payloads in publication order. */
    private List<String> rawListings(String restaurantId) {
        return jdbc().sql("""
                        SELECT payload FROM outbox_event WHERE aggregate_id = ? AND event_type = ?
                        ORDER BY occurred_at, id""")
                .param(restaurantId).param(LISTING).query(String.class).list();
    }

    private List<JsonNode> listings(String restaurantId) {
        return rawListings(restaurantId).stream().map(json::readTree).toList();
    }

    private JsonNode lastListing(String restaurantId) {
        return listings(restaurantId).getLast();
    }

    private List<Long> versions(String restaurantId) {
        return listings(restaurantId).stream().map(l -> l.get("listingVersion").asLong()).toList();
    }

    private long storedListingVersion(String restaurantId) {
        return jdbc().sql("SELECT listing_version FROM restaurant WHERE id = UUID_TO_BIN(?)")
                .param(restaurantId).query(Long.class).single();
    }

    private Set<String> keys(JsonNode node) {
        return json.readValue(node.toString(), Map.class).keySet();
    }

    private static List<String> names(JsonNode array) {
        List<String> names = new ArrayList<>();
        for (int i = 0; i < array.size(); i++) {
            names.add(array.get(i).get("name").asString());
        }
        return names;
    }

    // ---------------------------------------------------------------- tests

    @Test
    void menuBuiltWhileDraftGoesLiveWithTheApproval() throws Exception {
        UUID owner = UUID.randomUUID();
        String restaurantId = createProfile(owner);
        String biryani = createCategory(owner, "Biryani", 1);
        String starters = createCategory(owner, "Starters", 0);
        String chicken = createItem(owner, biryani, "Chicken Biryani", "249", "NON_VEG", 0);
        createItem(owner, biryani, "Veg Biryani", "199.5", "VEG", 0);
        String egg = createItem(owner, biryani, "Egg Biryani", "219.00", "EGG", 1);
        createItem(owner, starters, "Paneer Tikka", "229", "VEG", 0);
        send(owner, "PATCH", "{\"available\":false}", "/api/restaurant/menu/items/{id}/availability", egg)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(egg))
                .andExpect(jsonPath("$.available").value(false));

        // Sorted by sortOrder then name; prices always with 2 decimals; sold-out items stay on the menu
        mvc.perform(get("/api/restaurant/menu").with(as(owner, OWNER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.restaurantId").value(restaurantId))
                .andExpect(jsonPath("$.restaurantStatus").value("DRAFT"))
                .andExpect(jsonPath("$.acceptingOrders").value(true))
                .andExpect(jsonPath("$.itemCount").value(4))
                .andExpect(jsonPath("$.categories.length()").value(2))
                .andExpect(jsonPath("$.categories[0].id").value(starters))
                .andExpect(jsonPath("$.categories[0].name").value("Starters"))
                .andExpect(jsonPath("$.categories[0].description").value("Chef's picks"))
                .andExpect(jsonPath("$.categories[1].name").value("Biryani"))
                .andExpect(jsonPath("$.categories[1].sortOrder").value(1))
                .andExpect(jsonPath("$.categories[1].items.length()").value(3))
                .andExpect(jsonPath("$.categories[1].items[0].id").value(chicken))
                .andExpect(jsonPath("$.categories[1].items[0].foodType").value("NON_VEG"))
                .andExpect(jsonPath("$.categories[1].items[0].description").value("House special"))
                .andExpect(jsonPath("$.categories[1].items[0].updatedAt").isNotEmpty())
                .andExpect(jsonPath("$.categories[1].items[1].name").value("Veg Biryani"))
                .andExpect(jsonPath("$.categories[1].items[2].name").value("Egg Biryani"))
                .andExpect(jsonPath("$.categories[1].items[2].available").value(false))
                .andExpect(content().string(containsString("\"price\":249.00")))
                .andExpect(content().string(containsString("\"price\":199.50")));

        // The accepting-orders switch works before approval too; nothing is published while not approved
        send(owner, "PUT", "{\"acceptingOrders\":false}", "/api/restaurant/accepting-orders")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.acceptingOrders").value(false));
        send(owner, "PUT", "{\"acceptingOrders\":true}", "/api/restaurant/accepting-orders")
                .andExpect(jsonPath("$.acceptingOrders").value(true));
        assertThat(eventTypes(restaurantId)).containsExactly("RestaurantRegistered");

        // Admin reviews the menu, then approves: exactly one listing, with the menu
        mvc.perform(get("/api/admin/restaurants/{id}/menu", restaurantId).with(as(admin, "SUPPORT")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.restaurantStatus").value("DRAFT"))
                .andExpect(jsonPath("$.itemCount").value(4));
        approve(owner, restaurantId);

        List<String> types = eventTypes(restaurantId);
        assertThat(types).contains("RestaurantApproved");
        assertThat(types).filteredOn(LISTING::equals).hasSize(1);
        assertThat(storedListingVersion(restaurantId)).isEqualTo(1);

        JsonNode listing = lastListing(restaurantId);
        // Field names are the contract with catalog-query (docs/api/phase-3-catalog-and-search.md §8)
        assertThat(keys(listing)).containsExactlyInAnyOrder("restaurantId", "ownerUserId", "status", "name",
                "description", "cuisines", "address", "openingHours", "acceptingOrders", "menu", "listingVersion",
                "updatedAt");
        assertThat(listing.get("restaurantId").asString()).isEqualTo(restaurantId);
        assertThat(listing.get("ownerUserId").asString()).isEqualTo(owner.toString());
        assertThat(listing.get("status").asString()).isEqualTo("APPROVED");
        assertThat(listing.get("name").asString()).isEqualTo("Spice Route");
        assertThat(listing.get("description").asString()).isEqualTo("North Indian kitchen");
        assertThat(listing.get("cuisines").get(0).asString()).isEqualTo("NORTH_INDIAN");
        assertThat(listing.get("cuisines").get(1).asString()).isEqualTo("BIRYANI");
        assertThat(listing.get("acceptingOrders").asBoolean()).isTrue();
        assertThat(listing.get("listingVersion").asLong()).isEqualTo(1);
        assertThat(listing.get("updatedAt").asString()).isNotBlank();

        JsonNode address = listing.get("address");
        assertThat(keys(address)).containsExactlyInAnyOrder("line1", "line2", "city", "state", "postalCode",
                "country", "latitude", "longitude");
        assertThat(address.get("line1").asString()).isEqualTo("5 Church Street");
        assertThat(address.get("line2").isNull()).isTrue();
        assertThat(address.get("city").asString()).isEqualTo("Bengaluru");
        assertThat(address.get("postalCode").asString()).isEqualTo("560001");
        assertThat(address.get("country").asString()).isEqualTo("IN");
        assertThat(address.get("latitude").asDouble()).isEqualTo(12.9752);
        assertThat(address.get("longitude").asDouble()).isEqualTo(77.6050);

        JsonNode hours = listing.get("openingHours");
        assertThat(hours.size()).isEqualTo(2);
        assertThat(keys(hours.get(0))).containsExactlyInAnyOrder("dayOfWeek", "opensAt", "closesAt");
        assertThat(hours.get(0).get("dayOfWeek").asString()).isEqualTo("MONDAY");
        assertThat(hours.get(0).get("opensAt").asString()).isEqualTo("09:00");
        assertThat(hours.get(0).get("closesAt").asString()).isEqualTo("23:00");
        assertThat(hours.get(1).get("dayOfWeek").asString()).isEqualTo("SUNDAY");
        assertThat(hours.get(1).get("closesAt").asString()).isEqualTo("02:00");

        JsonNode menu = listing.get("menu");
        assertThat(names(menu)).containsExactly("Starters", "Biryani");
        assertThat(keys(menu.get(0))).containsExactlyInAnyOrder("categoryId", "name", "description", "sortOrder",
                "items");
        assertThat(menu.get(0).get("categoryId").asString()).isEqualTo(starters);
        assertThat(menu.get(1).get("sortOrder").asInt()).isEqualTo(1);
        JsonNode biryaniItems = menu.get(1).get("items");
        assertThat(names(biryaniItems)).containsExactly("Chicken Biryani", "Veg Biryani", "Egg Biryani");
        JsonNode first = biryaniItems.get(0);
        assertThat(keys(first)).containsExactlyInAnyOrder("itemId", "name", "description", "price", "foodType",
                "imageUrl", "available", "sortOrder");
        assertThat(first.get("itemId").asString()).isEqualTo(chicken);
        assertThat(first.get("price").decimalValue()).isEqualByComparingTo("249.00");
        assertThat(first.get("foodType").asString()).isEqualTo("NON_VEG");
        assertThat(first.get("available").asBoolean()).isTrue();
        assertThat(first.get("imageUrl").isNull()).isTrue();
        assertThat(biryaniItems.get(1).get("price").decimalValue()).isEqualByComparingTo("199.50");
        assertThat(biryaniItems.get(2).get("available").asBoolean()).isFalse();
        assertThat(biryaniItems.get(2).get("sortOrder").asInt()).isEqualTo(1);
    }

    @Test
    void everyChangeAfterApprovalPublishesTheNextListingVersion() throws Exception {
        UUID owner = UUID.randomUUID();
        String restaurantId = createProfile(owner);
        String mains = createCategory(owner, "Mains", 0);
        String dal = createItem(owner, mains, "Dal Makhani", "249", "VEG", 0);
        approve(owner, restaurantId);
        assertThat(versions(restaurantId)).containsExactly(1L);

        // Price change → v2 carries the new price
        send(owner, "PUT", item(mains, "Dal Makhani", "259.00", "VEG", 0), "/api/restaurant/menu/items/{id}", dal)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(dal))
                .andExpect(jsonPath("$.price").value(259.0));
        JsonNode v2 = lastListing(restaurantId);
        assertThat(v2.get("listingVersion").asLong()).isEqualTo(2);
        assertThat(v2.get("menu").get(0).get("items").get(0).get("price").decimalValue()).isEqualByComparingTo("259");

        // Sold out → v3; setting the same value again changes nothing
        send(owner, "PATCH", "{\"available\":false}", "/api/restaurant/menu/items/{id}/availability", dal)
                .andExpect(status().isOk());
        send(owner, "PATCH", "{\"available\":false}", "/api/restaurant/menu/items/{id}/availability", dal)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.available").value(false));
        JsonNode v3 = lastListing(restaurantId);
        assertThat(v3.get("listingVersion").asLong()).isEqualTo(3);
        assertThat(v3.get("menu").get(0).get("items").get(0).get("available").asBoolean()).isFalse();

        // Stop taking orders → v4; the restaurant views show the switch too
        send(owner, "PUT", "{\"acceptingOrders\":false}", "/api/restaurant/accepting-orders")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.acceptingOrders").value(false));
        send(owner, "PUT", "{\"acceptingOrders\":false}", "/api/restaurant/accepting-orders")
                .andExpect(status().isOk());
        JsonNode v4 = lastListing(restaurantId);
        assertThat(v4.get("listingVersion").asLong()).isEqualTo(4);
        assertThat(v4.get("acceptingOrders").asBoolean()).isFalse();
        mvc.perform(get("/api/restaurants/me").with(as(owner, OWNER)))
                .andExpect(jsonPath("$.acceptingOrders").value(false));
        mvc.perform(get("/api/admin/restaurants/{id}", restaurantId).with(as(admin, "SUPPORT")))
                .andExpect(jsonPath("$.acceptingOrders").value(false));
        mvc.perform(get("/api/restaurant/menu").with(as(owner, OWNER)))
                .andExpect(jsonPath("$.acceptingOrders").value(false))
                .andExpect(jsonPath("$.restaurantStatus").value("APPROVED"));

        // New category → v5, rename → v6, move the item → v7, delete the emptied category → v8, delete item → v9
        String sweets = createCategory(owner, "Desserts", 5);
        assertThat(names(lastListing(restaurantId).get("menu"))).containsExactly("Mains", "Desserts");
        send(owner, "PUT", category("Sweets", 5), "/api/restaurant/menu/categories/{id}", sweets)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(sweets))
                .andExpect(jsonPath("$.name").value("Sweets"))
                .andExpect(jsonPath("$.items").isEmpty());
        assertThat(names(lastListing(restaurantId).get("menu"))).containsExactly("Mains", "Sweets");
        send(owner, "PUT", item(sweets, "Dal Makhani", "259.00", "VEG", 0), "/api/restaurant/menu/items/{id}", dal)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.categoryId").value(sweets))
                .andExpect(jsonPath("$.available").value(true)); // PUT replaces everything; available defaults to true
        JsonNode v7 = lastListing(restaurantId);
        assertThat(v7.get("menu").get(0).get("items").size()).isZero();
        assertThat(names(v7.get("menu").get(1).get("items"))).containsExactly("Dal Makhani");
        mvc.perform(delete("/api/restaurant/menu/categories/{id}", mains).with(as(owner, OWNER)))
                .andExpect(status().isNoContent());
        assertThat(names(lastListing(restaurantId).get("menu"))).containsExactly("Sweets");
        mvc.perform(delete("/api/restaurant/menu/items/{id}", dal).with(as(owner, OWNER)))
                .andExpect(status().isNoContent());
        JsonNode v9 = lastListing(restaurantId);
        assertThat(v9.get("menu").get(0).get("items").size()).isZero();

        assertThat(versions(restaurantId)).containsExactly(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L, 9L);
        assertThat(storedListingVersion(restaurantId)).isEqualTo(9);
        mvc.perform(get("/api/restaurant/menu").with(as(owner, OWNER)))
                .andExpect(jsonPath("$.itemCount").value(0))
                .andExpect(jsonPath("$.categories.length()").value(1));
    }

    @Test
    void concurrentChangesGetDistinctVersionsAndNeverOlderData() throws Exception {
        UUID owner = UUID.randomUUID();
        String restaurantId = createProfile(owner);
        UUID mains = UUID.fromString(createCategory(owner, "Mains", 0));
        approve(owner, restaurantId); // v1, no items
        CurrentUser user = new CurrentUser(owner, "owner@example.com", "Owner", Set.of(OWNER), null, null);

        int writers = 8;
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(writers);
        try {
            List<Future<?>> results = new ArrayList<>();
            for (int i = 0; i < writers; i++) {
                MenuItemRequest request = new MenuItemRequest(mains, "Dish " + i, null, new BigDecimal("100"),
                        "VEG", null, null, i);
                results.add(pool.submit(() -> {
                    start.await();
                    return menuService.createItem(user, request);
                }));
            }
            start.countDown();
            for (Future<?> result : results) {
                result.get(60, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        // Row lock → one version per change, and version N always contains exactly the first N-1 dishes
        List<JsonNode> listings = listings(restaurantId).stream()
                .sorted(Comparator.comparingLong(l -> l.get("listingVersion").asLong())).toList();
        assertThat(listings).hasSize(writers + 1);
        for (int i = 0; i < listings.size(); i++) {
            JsonNode listing = listings.get(i);
            assertThat(listing.get("listingVersion").asLong()).isEqualTo(i + 1);
            assertThat(listing.get("menu").get(0).get("items").size()).isEqualTo(i);
        }
        assertThat(storedListingVersion(restaurantId)).isEqualTo(writers + 1);
    }

    @Test
    void limitsUniquenessAndValidationAreEnforced() throws Exception {
        UUID owner = UUID.randomUUID();
        String restaurantId = createProfile(owner);
        String biryani = createCategory(owner, "Biryani", 0);
        String starters = createCategory(owner, "Starters", 1);

        // Category names are unique per menu, ignoring case (and surrounding spaces)
        send(owner, "POST", category("biryani", 3), "/api/restaurant/menu/categories")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("MENU_CATEGORY_NAME_TAKEN"));
        send(owner, "POST", category("  BIRYANI ", 3), "/api/restaurant/menu/categories")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("MENU_CATEGORY_NAME_TAKEN"));
        send(owner, "PUT", category("BIRYANI", 1), "/api/restaurant/menu/categories/{id}", starters)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("MENU_CATEGORY_NAME_TAKEN"));
        send(owner, "PUT", category("BIRYANI", 0), "/api/restaurant/menu/categories/{id}", biryani)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("BIRYANI"));

        // A category with items cannot be deleted
        String dish = createItem(owner, biryani, "Mutton Biryani", "349", "NON_VEG", 0);
        mvc.perform(delete("/api/restaurant/menu/categories/{id}", biryani).with(as(owner, OWNER)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("MENU_CATEGORY_NOT_EMPTY"));

        // Unknown ids
        UUID unknown = UUID.randomUUID();
        send(owner, "PUT", category("Nope", 0), "/api/restaurant/menu/categories/{id}", unknown)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("MENU_CATEGORY_NOT_FOUND"));
        mvc.perform(delete("/api/restaurant/menu/categories/{id}", unknown).with(as(owner, OWNER)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("MENU_CATEGORY_NOT_FOUND"));
        send(owner, "POST", item(unknown.toString(), "Ghost", "100", "VEG", 0), "/api/restaurant/menu/items")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("MENU_CATEGORY_NOT_FOUND"));
        send(owner, "PUT", item(unknown.toString(), "Mutton Biryani", "349", "NON_VEG", 0),
                "/api/restaurant/menu/items/{id}", dish)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("MENU_CATEGORY_NOT_FOUND"));
        send(owner, "PUT", item(biryani, "Ghost", "100", "VEG", 0), "/api/restaurant/menu/items/{id}", unknown)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("MENU_ITEM_NOT_FOUND"));
        send(owner, "PATCH", "{\"available\":false}", "/api/restaurant/menu/items/{id}/availability", unknown)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("MENU_ITEM_NOT_FOUND"));
        mvc.perform(delete("/api/restaurant/menu/items/{id}", unknown).with(as(owner, OWNER)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("MENU_ITEM_NOT_FOUND"));

        // Validation
        send(owner, "POST", """
                {"categoryId":null,"name":" ","price":0.99,"foodType":"VEGAN","imageUrl":"ftp://img.example.com/a.png",
                 "sortOrder":10000}""", "/api/restaurant/menu/items")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors.length()").value(6))
                .andExpect(jsonPath("$.errors[?(@.field == 'categoryId')]").exists())
                .andExpect(jsonPath("$.errors[?(@.field == 'name')]").exists())
                .andExpect(jsonPath("$.errors[?(@.field == 'price')]").exists())
                .andExpect(jsonPath("$.errors[?(@.field == 'foodType')]").exists())
                .andExpect(jsonPath("$.errors[?(@.field == 'imageUrl')]").exists())
                .andExpect(jsonPath("$.errors[?(@.field == 'sortOrder')]").exists());
        for (String badPrice : List.of("100000.01", "12.345", "-5")) {
            send(owner, "POST", item(biryani, "Odd price", badPrice, "VEG", 0), "/api/restaurant/menu/items")
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errors[?(@.field == 'price')]").exists());
        }
        send(owner, "POST", item(biryani, "x".repeat(121), "100", "VEG", 0), "/api/restaurant/menu/items")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[?(@.field == 'name')]").exists());
        send(owner, "POST", "{\"categoryId\":\"%s\",\"name\":\"Kulfi\",\"price\":100000.00,\"foodType\":\"VEG\"}"
                .formatted(biryani), "/api/restaurant/menu/items")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.sortOrder").value(0))
                .andExpect(jsonPath("$.available").value(true));
        send(owner, "POST", "{\"name\":\"%s\",\"description\":\"%s\",\"sortOrder\":-1}"
                .formatted("x".repeat(81), "d".repeat(301)), "/api/restaurant/menu/categories")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.length()").value(3));
        send(owner, "POST", "{}", "/api/restaurant/menu/categories")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[?(@.field == 'name')]").exists());
        send(owner, "PATCH", "{}", "/api/restaurant/menu/items/{id}/availability", dish)
                .andExpect(status().isBadRequest());
        send(owner, "PUT", "{}", "/api/restaurant/accepting-orders")
                .andExpect(status().isBadRequest());

        // At most 30 categories
        for (int i = 2; i < 30; i++) {
            createCategory(owner, "Section " + i, i);
        }
        send(owner, "POST", category("One Too Many", 0), "/api/restaurant/menu/categories")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errorCode").value("MENU_LIMIT_REACHED"));

        // At most 500 items (2 created above + 498 inserted directly)
        jdbc().sql("""
                        INSERT INTO menu_item (id, restaurant_id, category_id, name, price, food_type, available,
                                               sort_order, created_at, updated_at, version)
                        WITH RECURSIVE seq (n) AS (SELECT 1 UNION ALL SELECT n + 1 FROM seq WHERE n < 498)
                        SELECT UUID_TO_BIN(UUID()), UUID_TO_BIN(?), UUID_TO_BIN(?), CONCAT('Bulk dish ', n), 99.00,
                               'VEG', TRUE, 0, NOW(6), NOW(6), 0
                        FROM seq""")
                .param(restaurantId).param(starters).update();
        mvc.perform(get("/api/restaurant/menu").with(as(owner, OWNER)))
                .andExpect(jsonPath("$.itemCount").value(500));
        send(owner, "POST", item(biryani, "Item 501", "100", "VEG", 0), "/api/restaurant/menu/items")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errorCode").value("MENU_LIMIT_REACHED"));

        // Nothing above was published: the restaurant was never approved
        assertThat(eventTypes(restaurantId)).doesNotContain(LISTING);
    }

    @Test
    void ownersCannotSeeOrTouchAnotherRestaurantsMenu() throws Exception {
        UUID ownerA = UUID.randomUUID();
        UUID ownerB = UUID.randomUUID();
        createProfile(ownerA);
        createProfile(ownerB);
        String categoryA = createCategory(ownerA, "Curries", 0);
        String itemA = createItem(ownerA, categoryA, "Butter Chicken", "299", "NON_VEG", 0);
        String categoryB = createCategory(ownerB, "Curries", 0); // same name in another menu is fine

        mvc.perform(get("/api/restaurant/menu").with(as(ownerB, OWNER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.itemCount").value(0))
                .andExpect(jsonPath("$.categories.length()").value(1))
                .andExpect(jsonPath("$.categories[0].id").value(categoryB));

        send(ownerB, "PUT", category("Mine now", 0), "/api/restaurant/menu/categories/{id}", categoryA)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("MENU_CATEGORY_NOT_FOUND"));
        mvc.perform(delete("/api/restaurant/menu/categories/{id}", categoryA).with(as(ownerB, OWNER)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("MENU_CATEGORY_NOT_FOUND"));
        send(ownerB, "POST", item(categoryA, "Intruder", "100", "VEG", 0), "/api/restaurant/menu/items")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("MENU_CATEGORY_NOT_FOUND"));
        send(ownerB, "PUT", item(categoryB, "Stolen", "1", "VEG", 0), "/api/restaurant/menu/items/{id}", itemA)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("MENU_ITEM_NOT_FOUND"));
        send(ownerB, "PATCH", "{\"available\":false}", "/api/restaurant/menu/items/{id}/availability", itemA)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("MENU_ITEM_NOT_FOUND"));
        mvc.perform(delete("/api/restaurant/menu/items/{id}", itemA).with(as(ownerB, OWNER)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("MENU_ITEM_NOT_FOUND"));
        // ...and A cannot move an item into B's category
        send(ownerA, "PUT", item(categoryB, "Butter Chicken", "299", "NON_VEG", 0),
                "/api/restaurant/menu/items/{id}", itemA)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("MENU_CATEGORY_NOT_FOUND"));

        mvc.perform(get("/api/restaurant/menu").with(as(ownerA, OWNER)))
                .andExpect(jsonPath("$.itemCount").value(1))
                .andExpect(jsonPath("$.categories[0].name").value("Curries"))
                .andExpect(jsonPath("$.categories[0].items[0].name").value("Butter Chicken"))
                .andExpect(jsonPath("$.categories[0].items[0].available").value(true));

        // No restaurant profile at all
        UUID stranger = UUID.randomUUID();
        mvc.perform(get("/api/restaurant/menu").with(as(stranger, OWNER)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("RESTAURANT_NOT_FOUND"));
        send(stranger, "POST", category("Anything", 0), "/api/restaurant/menu/categories")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("RESTAURANT_NOT_FOUND"));
        send(stranger, "PUT", "{\"acceptingOrders\":false}", "/api/restaurant/accepting-orders")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("RESTAURANT_NOT_FOUND"));
    }

    @Test
    void suspensionPublishesTheListingAndBlocksMenuChanges() throws Exception {
        UUID owner = UUID.randomUUID();
        String restaurantId = createProfile(owner);
        String curries = createCategory(owner, "Curries", 0);
        String dish = createItem(owner, curries, "Kadai Paneer", "279", "VEG", 0);
        approve(owner, restaurantId);

        mvc.perform(post("/api/admin/restaurants/{id}/suspend", restaurantId).with(as(admin, "OPS_ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"Hygiene complaint\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUSPENDED"));
        assertThat(eventTypes(restaurantId)).contains("RestaurantSuspended");
        JsonNode suspended = lastListing(restaurantId);
        assertThat(suspended.get("listingVersion").asLong()).isEqualTo(2);
        assertThat(suspended.get("status").asString()).isEqualTo("SUSPENDED");
        assertThat(names(suspended.get("menu").get(0).get("items"))).containsExactly("Kadai Paneer");

        // Every owner change is refused while suspended; reading still works
        String suspendedCode = "RESTAURANT_SUSPENDED";
        send(owner, "POST", category("Breads", 1), "/api/restaurant/menu/categories")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.errorCode").value(suspendedCode));
        send(owner, "PUT", category("Gravies", 0), "/api/restaurant/menu/categories/{id}", curries)
                .andExpect(status().isConflict()).andExpect(jsonPath("$.errorCode").value(suspendedCode));
        mvc.perform(delete("/api/restaurant/menu/categories/{id}", curries).with(as(owner, OWNER)))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.errorCode").value(suspendedCode));
        send(owner, "POST", item(curries, "Dal Tadka", "199", "VEG", 0), "/api/restaurant/menu/items")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.errorCode").value(suspendedCode));
        send(owner, "PUT", item(curries, "Kadai Paneer", "1", "VEG", 0), "/api/restaurant/menu/items/{id}", dish)
                .andExpect(status().isConflict()).andExpect(jsonPath("$.errorCode").value(suspendedCode));
        send(owner, "PATCH", "{\"available\":false}", "/api/restaurant/menu/items/{id}/availability", dish)
                .andExpect(status().isConflict()).andExpect(jsonPath("$.errorCode").value(suspendedCode));
        mvc.perform(delete("/api/restaurant/menu/items/{id}", dish).with(as(owner, OWNER)))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.errorCode").value(suspendedCode));
        send(owner, "PUT", "{\"acceptingOrders\":false}", "/api/restaurant/accepting-orders")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.errorCode").value(suspendedCode));
        mvc.perform(get("/api/restaurant/menu").with(as(owner, OWNER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.restaurantStatus").value("SUSPENDED"))
                .andExpect(jsonPath("$.itemCount").value(1));
        assertThat(versions(restaurantId)).containsExactly(1L, 2L);

        // Reinstating publishes APPROVED again and unlocks the menu
        mvc.perform(post("/api/admin/restaurants/{id}/reinstate", restaurantId).with(as(admin, "SUPER_ADMIN")))
                .andExpect(status().isOk());
        assertThat(eventTypes(restaurantId)).contains("RestaurantReinstated");
        assertThat(lastListing(restaurantId).get("status").asString()).isEqualTo("APPROVED");
        send(owner, "PATCH", "{\"available\":false}", "/api/restaurant/menu/items/{id}/availability", dish)
                .andExpect(status().isOk());
        assertThat(versions(restaurantId)).containsExactly(1L, 2L, 3L, 4L);
        assertThat(lastListing(restaurantId).get("menu").get(0).get("items").get(0).get("available").asBoolean())
                .isFalse();
    }

    @Test
    void republishResendsEveryListingWithoutChangingVersions() throws Exception {
        UUID ownerA = UUID.randomUUID();
        String restaurantA = createProfile(ownerA);
        String category = createCategory(ownerA, "Rolls", 0);
        approve(ownerA, restaurantA);
        createItem(ownerA, category, "Kathi Roll", "149", "NON_VEG", 0); // v2
        UUID ownerB = UUID.randomUUID();
        String restaurantB = createProfile(ownerB);
        approve(ownerB, restaurantB); // v1, empty menu
        UUID ownerC = UUID.randomUUID();
        String draft = createProfile(ownerC);
        createCategory(ownerC, "Soon", 0);

        String lastA = rawListings(restaurantA).getLast();
        String lastB = rawListings(restaurantB).getLast();
        long listed = jdbc().sql("SELECT COUNT(*) FROM restaurant WHERE approved_at IS NOT NULL")
                .query(Long.class).single();
        long listingEventsBefore = jdbc().sql("SELECT COUNT(*) FROM outbox_event WHERE event_type = ?")
                .param(LISTING).query(Long.class).single();

        mvc.perform(post("/api/admin/restaurants/listings/republish").with(as(admin, "SUPPORT")))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/admin/restaurants/listings/republish").with(as(ownerA, OWNER)))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/admin/restaurants/listings/republish"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/admin/restaurants/listings/republish").with(as(admin, "OPS_ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.restaurants").value((int) listed));

        // One event per listed restaurant (batches of 2 in tests), identical to the last one: same version
        long listingEventsAfter = jdbc().sql("SELECT COUNT(*) FROM outbox_event WHERE event_type = ?")
                .param(LISTING).query(Long.class).single();
        assertThat(listingEventsAfter - listingEventsBefore).isEqualTo(listed);
        assertThat(listed).isGreaterThanOrEqualTo(2);
        assertThat(versions(restaurantA)).containsExactly(1L, 2L, 2L);
        assertThat(versions(restaurantB)).containsExactly(1L, 1L);
        assertThat(json.readTree(rawListings(restaurantA).getLast())).isEqualTo(json.readTree(lastA));
        assertThat(json.readTree(rawListings(restaurantB).getLast())).isEqualTo(json.readTree(lastB));
        assertThat(storedListingVersion(restaurantA)).isEqualTo(2);
        assertThat(storedListingVersion(restaurantB)).isEqualTo(1);
        assertThat(eventTypes(draft)).doesNotContain(LISTING);
    }

    @Test
    void adminMenuViewAndRoleChecks() throws Exception {
        UUID owner = UUID.randomUUID();
        String restaurantId = createProfile(owner);
        String sides = createCategory(owner, "Sides", 0);
        createItem(owner, sides, "Raita", "49", "VEG", 0);

        mvc.perform(get("/api/restaurants/me").with(as(owner, OWNER)))
                .andExpect(jsonPath("$.acceptingOrders").value(true));
        for (String role : List.of("SUPPORT", "FINANCE_ADMIN", "DELIVERY_MANAGER", "OPS_ADMIN", "SUPER_ADMIN")) {
            mvc.perform(get("/api/admin/restaurants/{id}/menu", restaurantId).with(as(admin, role)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.restaurantId").value(restaurantId))
                    .andExpect(jsonPath("$.restaurantStatus").value("DRAFT"))
                    .andExpect(jsonPath("$.itemCount").value(1))
                    .andExpect(jsonPath("$.categories[0].items[0].name").value("Raita"));
        }
        mvc.perform(get("/api/admin/restaurants/{id}/menu", UUID.randomUUID()).with(as(admin, "SUPPORT")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("RESTAURANT_NOT_FOUND"));
        mvc.perform(get("/api/admin/restaurants/{id}/menu", restaurantId).with(as(owner, OWNER)))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/admin/restaurants/{id}/menu", restaurantId))
                .andExpect(status().isUnauthorized());

        // Owner endpoints need the RESTAURANT_OWNER role
        for (String role : List.of("OPS_ADMIN", "VENDOR", "CUSTOMER")) {
            UUID someone = UUID.randomUUID();
            mvc.perform(get("/api/restaurant/menu").with(as(someone, role)))
                    .andExpect(status().isForbidden());
            mvc.perform(post("/api/restaurant/menu/categories").with(as(someone, role))
                            .contentType(MediaType.APPLICATION_JSON).content(category("X", 0)))
                    .andExpect(status().isForbidden());
            mvc.perform(put("/api/restaurant/accepting-orders").with(as(someone, role))
                            .contentType(MediaType.APPLICATION_JSON).content("{\"acceptingOrders\":false}"))
                    .andExpect(status().isForbidden());
        }
        mvc.perform(get("/api/restaurant/menu"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("UNAUTHENTICATED"));
        mvc.perform(put("/api/restaurant/accepting-orders")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"acceptingOrders\":false}"))
                .andExpect(status().isUnauthorized());
    }
}
