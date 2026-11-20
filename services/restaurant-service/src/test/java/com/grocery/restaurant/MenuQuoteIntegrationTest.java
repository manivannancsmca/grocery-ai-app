package com.grocery.restaurant;

import com.grocery.restaurant.repository.RestaurantRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

import java.time.Duration;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Phase 5: {@code POST /internal/restaurants/{restaurantId}/menu-quote} — authoritative restaurant data for the
 * cart (docs/api/phase-5-cart-and-checkout-preview.md §4).
 */
class MenuQuoteIntegrationTest extends AbstractIntegrationTest {

    private static final String OWNER = "RESTAURANT_OWNER";
    private static final String QUOTE = "/internal/restaurants/{restaurantId}/menu-quote";
    private static final ZoneId INDIA = ZoneId.of("Asia/Kolkata");
    /** Open SUNDAY 18:00–02:00 (overnight) and MONDAY 09:00–23:00; closed the rest of the week. */
    private static final String PROFILE = """
            {"name":"Spice Route","description":"North Indian kitchen","cuisines":["NORTH_INDIAN","BIRYANI"],
             "fssaiLicense":"12345678901234","contactEmail":"ops@spiceroute.in","contactPhone":"+919876543210",
             "address":{"line1":"5 Church Street","city":"Bengaluru","state":"Karnataka","postalCode":"560001",
                        "country":"IN","latitude":12.9784,"longitude":77.6408},
             "openingHours":[{"dayOfWeek":"SUNDAY","opensAt":"18:00","closesAt":"02:00"},
                             {"dayOfWeek":"MONDAY","opensAt":"09:00","closesAt":"23:00"}]}""";

    @Autowired
    RestaurantRepository restaurants;
    @Autowired
    PlatformTransactionManager transactionManager;

    private final UUID admin = UUID.randomUUID();
    private final UUID caller = UUID.randomUUID();

    // ---------------------------------------------------------------- helpers

    private String createProfile(UUID owner) throws Exception {
        return idOf(mvc.perform(put("/api/restaurants/me").with(as(owner, OWNER))
                .contentType(MediaType.APPLICATION_JSON).content(PROFILE)).andExpect(status().isOk()));
    }

    private void approve(UUID owner, String restaurantId) throws Exception {
        mvc.perform(post("/api/restaurants/me/submit").with(as(owner, OWNER))).andExpect(status().isOk());
        mvc.perform(post("/api/admin/restaurants/{id}/approve", restaurantId).with(as(admin, "OPS_ADMIN")))
                .andExpect(status().isOk());
    }

    private String createCategory(UUID owner, String name) throws Exception {
        return idOf(mvc.perform(post("/api/restaurant/menu/categories").with(as(owner, OWNER))
                .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"" + name + "\"}"))
                .andExpect(status().isCreated()));
    }

    private String createItem(UUID owner, String categoryId, String name, String price, String foodType,
                              String imageUrl) throws Exception {
        String body = """
                {"categoryId":"%s","name":"%s","price":%s,"foodType":"%s","imageUrl":%s}"""
                .formatted(categoryId, name, price, foodType, imageUrl == null ? "null" : "\"" + imageUrl + "\"");
        return idOf(mvc.perform(post("/api/restaurant/menu/items").with(as(owner, OWNER))
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isCreated()));
    }

    private String idOf(ResultActions result) throws Exception {
        return json.readTree(result.andReturn().getResponse().getContentAsString()).get("id").asString();
    }

    private ResultActions quote(String restaurantId, String... itemIds) throws Exception {
        return quoteBody(restaurantId, "{\"itemIds\":[" + Arrays.stream(itemIds).map(id -> "\"" + id + "\"")
                .collect(Collectors.joining(",")) + "]}");
    }

    private ResultActions quoteBody(String restaurantId, String body) throws Exception {
        return mvc.perform(post(QUOTE, restaurantId).with(as(caller, "CUSTOMER"))
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private boolean openNowAt(String restaurantId, String itemId, ZonedDateTime now) throws Exception {
        clock.set(now.toInstant());
        return json.readTree(quote(restaurantId, itemId).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString()).get("openNow").asBoolean();
    }

    /** 2026-11-15 is a Sunday. */
    private static ZonedDateTime india(int dayOfMonth, int hour, int minute) {
        return ZonedDateTime.of(2026, 11, dayOfMonth, hour, minute, 0, 0, INDIA);
    }

    private static List<String> strings(JsonNode array) {
        List<String> values = new ArrayList<>();
        array.forEach(value -> values.add(value.asString()));
        return values;
    }

    // ---------------------------------------------------------------- tests

    @Test
    void quotesTheRequestedItemsOfThisRestaurantAndReportsTheRestMissing() throws Exception {
        UUID owner = UUID.randomUUID();
        String restaurantId = createProfile(owner);
        String mains = createCategory(owner, "Biryani");
        String chicken = createItem(owner, mains, "Chicken Biryani", "249", "NON_VEG",
                "https://img.example.com/chicken-biryani.jpg");
        String egg = createItem(owner, mains, "Egg Biryani", "219.5", "EGG", null);
        mvc.perform(patch("/api/restaurant/menu/items/{id}/availability", egg).with(as(owner, OWNER))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"available\":false}"))
                .andExpect(status().isOk());
        approve(owner, restaurantId);

        UUID otherOwner = UUID.randomUUID();
        createProfile(otherOwner);
        String othersItem = createItem(otherOwner, createCategory(otherOwner, "Starters"), "Paneer Tikka", "229",
                "VEG", null);
        String unknown = UUID.randomUUID().toString();

        clock.set(india(16, 12, 0).toInstant());                                       // Monday noon: open
        JsonNode quote = json.readTree(quote(restaurantId, egg, othersItem, chicken, unknown, egg)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.restaurantId").value(restaurantId))
                .andExpect(jsonPath("$.name").value("Spice Route"))
                .andExpect(jsonPath("$.status").value("APPROVED"))
                .andExpect(jsonPath("$.acceptingOrders").value(true))
                .andExpect(jsonPath("$.openNow").value(true))
                .andExpect(jsonPath("$.city").value("Bengaluru"))
                .andExpect(jsonPath("$.latitude").value(12.9784))
                .andExpect(jsonPath("$.longitude").value(77.6408))
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].itemId").value(egg))
                .andExpect(jsonPath("$.items[0].name").value("Egg Biryani"))
                .andExpect(jsonPath("$.items[0].price").value(219.5))
                .andExpect(jsonPath("$.items[0].foodType").value("EGG"))
                .andExpect(jsonPath("$.items[0].available").value(false))
                .andExpect(jsonPath("$.items[0].imageUrl").isEmpty())
                .andExpect(jsonPath("$.items[1].itemId").value(chicken))
                .andExpect(jsonPath("$.items[1].name").value("Chicken Biryani"))
                .andExpect(jsonPath("$.items[1].price").value(249.0))
                .andExpect(jsonPath("$.items[1].foodType").value("NON_VEG"))
                .andExpect(jsonPath("$.items[1].available").value(true))
                .andExpect(jsonPath("$.items[1].imageUrl").value("https://img.example.com/chicken-biryani.jpg"))
                .andExpect(content().string(containsString("\"price\":249.00")))       // always 2 decimals
                .andExpect(content().string(containsString("\"price\":219.50")))
                .andReturn().getResponse().getContentAsString());
        assertThat(strings(quote.get("missing"))).containsExactly(othersItem, unknown);
        assertThat(json.readValue(quote.get("items").get(0).toString(), Map.class).keySet())
                .containsExactlyInAnyOrder("itemId", "name", "price", "foodType", "available", "imageUrl");
        assertThat(json.readValue(quote.toString(), Map.class).keySet()).containsExactlyInAnyOrder("restaurantId",
                "name", "status", "acceptingOrders", "openNow", "city", "latitude", "longitude", "items", "missing");

        // the other restaurant sees its own item and not ours
        String otherId = restaurants.findByOwnerUserId(otherOwner).orElseThrow().getId().toString();
        JsonNode other = json.readTree(quote(otherId, othersItem, chicken).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DRAFT"))       // any status is quoted; the cart decides
                .andExpect(jsonPath("$.items[0].itemId").value(othersItem))
                .andReturn().getResponse().getContentAsString());
        assertThat(strings(other.get("missing"))).containsExactly(chicken);
    }

    @Test
    void acceptingOrdersAndStatusAreLive() throws Exception {
        UUID owner = UUID.randomUUID();
        String restaurantId = createProfile(owner);
        String item = createItem(owner, createCategory(owner, "Mains"), "Dal Makhani", "189", "VEG", null);
        approve(owner, restaurantId);

        mvc.perform(put("/api/restaurant/accepting-orders").with(as(owner, OWNER))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"acceptingOrders\":false}"))
                .andExpect(status().isOk());
        quote(restaurantId, item)
                .andExpect(jsonPath("$.acceptingOrders").value(false))
                .andExpect(jsonPath("$.status").value("APPROVED"));

        mvc.perform(post("/api/admin/restaurants/{id}/suspend", restaurantId).with(as(admin, "OPS_ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"Hygiene complaint\"}"))
                .andExpect(status().isOk());
        quote(restaurantId, item)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUSPENDED"))
                .andExpect(jsonPath("$.items[0].itemId").value(item));
    }

    @Test
    void openNowFollowsTheWeeklyHoursInIndiaTimeIncludingOvernightHours() throws Exception {
        UUID owner = UUID.randomUUID();
        String restaurantId = createProfile(owner);
        String item = createItem(owner, createCategory(owner, "Mains"), "Dal Makhani", "189", "VEG", null);

        assertThat(openNowAt(restaurantId, item, india(15, 17, 59))).isFalse();   // Sunday before opening
        assertThat(openNowAt(restaurantId, item, india(15, 18, 0))).isTrue();     // Sunday 18:00 opens
        assertThat(openNowAt(restaurantId, item, india(15, 23, 59))).isTrue();
        assertThat(openNowAt(restaurantId, item, india(16, 0, 30))).isTrue();     // Monday 00:30 = Sunday's night
        assertThat(openNowAt(restaurantId, item, india(16, 1, 59))).isTrue();
        assertThat(openNowAt(restaurantId, item, india(16, 2, 0))).isFalse();     // Sunday's hours end at 02:00
        assertThat(openNowAt(restaurantId, item, india(16, 8, 59))).isFalse();
        assertThat(openNowAt(restaurantId, item, india(16, 9, 0))).isTrue();      // Monday 09:00–23:00
        assertThat(openNowAt(restaurantId, item, india(16, 23, 0))).isFalse();
        assertThat(openNowAt(restaurantId, item, india(17, 0, 30))).isFalse();    // Monday closes before midnight
        assertThat(openNowAt(restaurantId, item, india(17, 12, 0))).isFalse();    // Tuesday: no hours → closed
        assertThat(openNowAt(restaurantId, item, india(21, 20, 0))).isFalse();    // Saturday: no hours → closed
        // Monday 04:00 UTC is 09:30 in India (open), although it is not yet 09:00 in UTC
        clock.set(ZonedDateTime.of(2026, 11, 16, 4, 0, 0, 0, ZoneId.of("UTC")).toInstant());
        quote(restaurantId, item).andExpect(jsonPath("$.openNow").value(true));
    }

    @Test
    void unknownRestaurantIsNotFound() throws Exception {
        quote(UUID.randomUUID().toString(), UUID.randomUUID().toString())
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("RESTAURANT_NOT_FOUND"));
        quote("not-a-uuid", UUID.randomUUID().toString()).andExpect(status().isBadRequest());
    }

    @Test
    void between1And100ItemIdsAreAccepted() throws Exception {
        UUID owner = UUID.randomUUID();
        String restaurantId = createProfile(owner);
        String item = createItem(owner, createCategory(owner, "Mains"), "Dal Makhani", "189", "VEG", null);

        String[] hundred = IntStream.range(0, 100).mapToObj(i -> i == 0 ? item : UUID.randomUUID().toString())
                .toArray(String[]::new);
        quote(restaurantId, hundred)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.missing.length()").value(99));

        String[] tooMany = IntStream.range(0, 101).mapToObj(i -> UUID.randomUUID().toString()).toArray(String[]::new);
        quote(restaurantId, tooMany)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].field").value("itemIds"));
        for (String body : List.of("{\"itemIds\":[]}", "{}", "{\"itemIds\":null}",
                "{\"itemIds\":[\"" + item + "\",null]}")) {
            quoteBody(restaurantId, body)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"));
        }
        quoteBody(restaurantId, "{\"itemIds\":[\"not-a-uuid\"]}").andExpect(status().isBadRequest());
    }

    @Test
    void internalPathsNeedATokenButNoParticularRole() throws Exception {
        UUID owner = UUID.randomUUID();
        String restaurantId = createProfile(owner);
        String item = createItem(owner, createCategory(owner, "Mains"), "Dal Makhani", "189", "VEG", null);
        String body = "{\"itemIds\":[\"" + item + "\"]}";

        mvc.perform(post(QUOTE, restaurantId).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("UNAUTHENTICATED"));
        for (String role : List.of("CUSTOMER", "VENDOR", "SUPPORT")) {
            mvc.perform(post(QUOTE, restaurantId).with(as(UUID.randomUUID(), role))
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.items[0].itemId").value(item));
        }
    }

    @Test
    void theQuoteTakesNoLocksSoItDoesNotWaitForAnOwnersChange() throws Exception {
        UUID owner = UUID.randomUUID();
        String restaurantId = createProfile(owner);
        String item = createItem(owner, createCategory(owner, "Mains"), "Dal Makhani", "189", "VEG", null);

        // Another transaction holds the restaurant row's FOR UPDATE lock (as every menu change does)
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<?> holder = pool.submit(() -> new TransactionTemplate(transactionManager).executeWithoutResult(tx -> {
                restaurants.findByIdForUpdate(UUID.fromString(restaurantId)).orElseThrow();
                locked.countDown();
                try {
                    release.await(30, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }));
            assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();

            long started = System.nanoTime();
            quote(restaurantId, item)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.items[0].itemId").value(item));
            assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(5));

            release.countDown();
            holder.get(10, TimeUnit.SECONDS);
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }
}
