package com.grocery.pricing;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Coupon CRUD, validation, scopes and roles (Phase 12 §2): admins, vendors, restaurant owners, customer offers. */
class CouponManagementIntegrationTest extends AbstractIntegrationTest {

    private ResultActions putJson(String path, RequestPostProcessor caller, String body) throws Exception {
        return mvc.perform(put(path).with(caller).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private static String flatBody(String code, String extra) {
        return "{\"code\":\"%s\",\"name\":\"Flat 50\",\"discountType\":\"FLAT\",\"value\":50,%s%s}"
                .formatted(code, validWindow(), extra.isEmpty() ? "" : "," + extra);
    }

    // ---------------------------------------------------------------- admin

    @Test
    void adminCreatesAPlatformCouponWithTheDefaults() throws Exception {
        String code = code("save");
        Instant validUntil = Instant.now().plusSeconds(86_400).truncatedTo(ChronoUnit.SECONDS);
        createCoupon("/api/admin/coupons", opsAdmin(), """
                {"code":"%s","name":"Ten percent","description":"Ten percent off","discountType":"PERCENT",
                 "value":10,"maxDiscount":75,"validUntil":"%s"}""".formatted(code.toLowerCase(), validUntil))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNotEmpty())
                .andExpect(jsonPath("$.code").value(code))                       // stored upper-case
                .andExpect(jsonPath("$.name").value("Ten percent"))
                .andExpect(jsonPath("$.description").value("Ten percent off"))
                .andExpect(jsonPath("$.scope").value("PLATFORM"))
                .andExpect(jsonPath("$.storeType").value("ANY"))
                .andExpect(jsonPath("$.storeId").isEmpty())
                .andExpect(jsonPath("$.fundedBy").value("PLATFORM"))
                .andExpect(jsonPath("$.discountType").value("PERCENT"))
                .andExpect(jsonPath("$.value").value(10.00))
                .andExpect(jsonPath("$.maxDiscount").value(75.00))
                .andExpect(jsonPath("$.minOrderSubtotal").value(0.00))
                .andExpect(jsonPath("$.validFrom").isNotEmpty())                 // defaults to now
                .andExpect(jsonPath("$.validUntil").value(validUntil.toString()))
                .andExpect(jsonPath("$.totalUsageLimit").isEmpty())
                .andExpect(jsonPath("$.perCustomerLimit").value(1))
                .andExpect(jsonPath("$.active").value(true))
                .andExpect(jsonPath("$.public").value(false))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.usedCount").value(0))
                .andExpect(jsonPath("$.discountGiven").value(0.00))
                .andExpect(jsonPath("$.createdBy").value(admin.toString()));
    }

    @Test
    void validationErrors() throws Exception {
        // bean validation: code pattern, name length, missing validUntil
        createCoupon("/api/admin/coupons", opsAdmin(),
                "{\"code\":\"no spaces!\",\"name\":\"x\",\"discountType\":\"FLAT\",\"value\":5," + validWindow() + "}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].field").value("code"));
        createCoupon("/api/admin/coupons", opsAdmin(),
                "{\"code\":\"ABC\",\"name\":\"x\",\"discountType\":\"FLAT\",\"value\":5," + validWindow() + "}")
                .andExpect(status().isBadRequest());                              // < 4 characters
        createCoupon("/api/admin/coupons", opsAdmin(), "{\"code\":\"%s\",\"name\":\"%s\",\"discountType\":\"FLAT\",\"value\":5,%s}"
                .formatted(code("long"), "n".repeat(81), validWindow()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("name"));
        createCoupon("/api/admin/coupons", opsAdmin(),
                "{\"code\":\"%s\",\"name\":\"x\",\"discountType\":\"FLAT\",\"value\":5}".formatted(code("until")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("validUntil"));
        // rules across fields
        createCoupon("/api/admin/coupons", opsAdmin(),
                "{\"code\":\"%s\",\"name\":\"x\",\"discountType\":\"PERCENT\",\"value\":95,%s}"
                        .formatted(code("pct"), validWindow()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].field").value("value"))
                .andExpect(jsonPath("$.errors[0].message").value("must be between 1 and 90 for PERCENT"));
        createCoupon("/api/admin/coupons", opsAdmin(),
                "{\"code\":\"%s\",\"name\":\"x\",\"discountType\":\"FLAT\",%s}".formatted(code("flat"), validWindow()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].message").value("is required for FLAT"));
        Instant now = Instant.now();
        createCoupon("/api/admin/coupons", opsAdmin(),
                "{\"code\":\"%s\",\"name\":\"x\",\"discountType\":\"FREE_DELIVERY\",\"validFrom\":\"%s\",\"validUntil\":\"%s\"}"
                        .formatted(code("win"), now, now.minusSeconds(1)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("validUntil"));
        createCoupon("/api/admin/coupons", opsAdmin(), flatBody(code("store"), "\"scope\":\"STORE\""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("storeId"))
                .andExpect(jsonPath("$.errors.length()").value(1));
        createCoupon("/api/admin/coupons", opsAdmin(), flatBody(code("store"),
                "\"scope\":\"STORE\",\"storeType\":\"ANY\",\"storeId\":\"%s\"".formatted(UUID.randomUUID())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("storeType"));
        createCoupon("/api/admin/coupons", opsAdmin(),
                flatBody(code("plat"), "\"storeId\":\"%s\"".formatted(UUID.randomUUID())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].message").value("must be empty for a PLATFORM coupon"));
        createCoupon("/api/admin/coupons", opsAdmin(), flatBody(code("lim"), "\"perCustomerLimit\":0"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void codesAreUniqueIgnoringCase() throws Exception {
        String code = code("uniq");
        adminCoupon("\"code\":\"%s\",\"name\":\"A\",\"discountType\":\"FREE_DELIVERY\",%s".formatted(code, validWindow()));
        createCoupon("/api/admin/coupons", opsAdmin(), flatBody(code.toLowerCase(), ""))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("COUPON_CODE_TAKEN"));

        JsonNode other = adminCoupon("\"code\":\"%s\",\"name\":\"B\",\"discountType\":\"FREE_DELIVERY\",%s"
                .formatted(code("other"), validWindow()));
        putJson("/api/admin/coupons/" + other.get("id").asString(), opsAdmin(), flatBody(code, ""))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("COUPON_CODE_TAKEN"));
    }

    @Test
    void adminRoles() throws Exception {
        String body = flatBody(code("role"), "");
        createCoupon("/api/admin/coupons", as(UUID.randomUUID(), "SUPPORT"), body).andExpect(status().isForbidden());
        createCoupon("/api/admin/coupons", as(UUID.randomUUID(), "DELIVERY_MANAGER"), body)
                .andExpect(status().isForbidden());
        createCoupon("/api/admin/coupons", customer(UUID.randomUUID()), body).andExpect(status().isForbidden());
        createCoupon("/api/admin/coupons", vendor(UUID.randomUUID()), body).andExpect(status().isForbidden());
        String id = body(createCoupon("/api/admin/coupons", as(UUID.randomUUID(), "FINANCE_ADMIN"), body)
                .andExpect(status().isCreated())).get("id").asString();
        createCoupon("/api/admin/coupons", as(UUID.randomUUID(), "SUPER_ADMIN"), flatBody(code("role"), ""))
                .andExpect(status().isCreated());

        // any admin reads; SUPPORT cannot pause
        mvc.perform(get("/api/admin/coupons/{id}", id).with(as(UUID.randomUUID(), "SUPPORT")))
                .andExpect(status().isOk());
        mvc.perform(get("/api/admin/coupons/{id}/usage", id).with(as(UUID.randomUUID(), "DELIVERY_MANAGER")))
                .andExpect(status().isOk());
        mvc.perform(post("/api/admin/coupons/{id}/pause", id).with(as(UUID.randomUUID(), "SUPPORT")))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/admin/coupons/{id}", UUID.randomUUID()).with(opsAdmin()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("COUPON_NOT_FOUND"));
    }

    @Test
    void updateReplacesTheTermsAndKeepsOmittedDefaults() throws Exception {
        String code = code("upd");
        JsonNode created = adminCoupon("""
                "code":"%s","name":"Old","discountType":"PERCENT","value":20,"maxDiscount":100,"minOrderSubtotal":199,
                "perCustomerLimit":3,"totalUsageLimit":50,"public":true,"storeType":"GROCERY",%s"""
                .formatted(code, validWindow()));
        String id = created.get("id").asString();
        String newCode = code("renamed");

        putJson("/api/admin/coupons/" + id, opsAdmin(), """
                {"code":"%s","name":"New","discountType":"FLAT","value":40,"maxDiscount":10,"validUntil":"%s"}"""
                .formatted(newCode, Instant.now().plusSeconds(3600)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(newCode))
                .andExpect(jsonPath("$.name").value("New"))
                .andExpect(jsonPath("$.discountType").value("FLAT"))
                .andExpect(jsonPath("$.value").value(40.00))
                .andExpect(jsonPath("$.maxDiscount").isEmpty())                  // not for FLAT
                .andExpect(jsonPath("$.totalUsageLimit").isEmpty())              // replaced: no limit
                // kept: defaulted fields that were left out
                .andExpect(jsonPath("$.minOrderSubtotal").value(199.00))
                .andExpect(jsonPath("$.perCustomerLimit").value(3))
                .andExpect(jsonPath("$.public").value(true))
                .andExpect(jsonPath("$.storeType").value("GROCERY"))
                .andExpect(jsonPath("$.validFrom").value(created.get("validFrom").asString()))
                .andExpect(jsonPath("$.createdAt").value(created.get("createdAt").asString()));
    }

    @Test
    void anAdminsStoreCouponNeedsOnlyTheStoreId() throws Exception {
        UUID vendorId = UUID.randomUUID();
        product(vendorId, "Rice");                                             // known vendor "Green Basket"
        UUID restaurantId = UUID.randomUUID();
        createCoupon("/api/admin/coupons", opsAdmin(), flatBody(code("adst"),
                "\"scope\":\"STORE\",\"storeId\":\"%s\",\"storeType\":null".formatted(vendorId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.scope").value("STORE"))
                .andExpect(jsonPath("$.storeId").value(vendorId.toString()))
                .andExpect(jsonPath("$.storeType").value("GROCERY"))
                .andExpect(jsonPath("$.storeName").value("Green Basket"))
                .andExpect(jsonPath("$.fundedBy").value("STORE"));
        String id = body(createCoupon("/api/admin/coupons", opsAdmin(), flatBody(code("adst"),
                "\"scope\":\"STORE\",\"storeId\":\"%s\"".formatted(restaurantId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.storeType").value("RESTAURANT"))
                .andExpect(jsonPath("$.storeName").isEmpty()))
                .get("id").asString();
        // the restaurant owner manages it like their own
        mvc.perform(get("/api/restaurant/coupons/{id}", id).with(restaurantOwner(restaurantId)))
                .andExpect(status().isOk());
        // a PLATFORM coupon has no store
        createCoupon("/api/admin/coupons", opsAdmin(), flatBody(code("adpl"),
                "\"scope\":\"PLATFORM\",\"storeType\":\"RESTAURANT\",\"storeId\":null"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.storeType").value("RESTAURANT"))
                .andExpect(jsonPath("$.storeId").isEmpty())
                .andExpect(jsonPath("$.storeName").isEmpty());
    }

    @Test
    void pauseAndResumeAreIdempotent() throws Exception {
        String id = adminCoupon("\"code\":\"%s\",\"name\":\"P\",\"discountType\":\"FREE_DELIVERY\",%s"
                .formatted(code("pause"), validWindow())).get("id").asString();
        for (int i = 0; i < 2; i++) {
            mvc.perform(post("/api/admin/coupons/{id}/pause", id).with(opsAdmin()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.active").value(false))
                    .andExpect(jsonPath("$.status").value("PAUSED"));
        }
        mvc.perform(post("/api/admin/coupons/{id}/resume", id).with(opsAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(true))
                .andExpect(jsonPath("$.status").value("ACTIVE"));
    }

    @Test
    void adminListFilters() throws Exception {
        UUID vendorId = UUID.randomUUID();
        String storeCode = body(createCoupon("/api/vendor/coupons", vendor(vendorId), flatBody(code("vlist"), ""))
                .andExpect(status().isCreated())).get("code").asString();
        String name = "Filter " + UUID.randomUUID().toString().substring(0, 8);
        String pausedId = adminCoupon("\"code\":\"%s\",\"name\":\"%s\",\"discountType\":\"FREE_DELIVERY\",\"active\":false,%s"
                .formatted(code("flist"), name, validWindow())).get("id").asString();
        Instant now = Instant.now();
        adminCoupon("\"code\":\"%s\",\"name\":\"%s\",\"discountType\":\"FREE_DELIVERY\",\"validFrom\":\"%s\",\"validUntil\":\"%s\""
                .formatted(code("flist"), name, now.plusSeconds(3600), now.plusSeconds(7200)));

        mvc.perform(get("/api/admin/coupons").param("storeId", vendorId.toString()).with(opsAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].code").value(storeCode))
                .andExpect(jsonPath("$.content[0].scope").value("STORE"))
                .andExpect(jsonPath("$.content[0].fundedBy").value("STORE"));
        mvc.perform(get("/api/admin/coupons").param("q", name.toLowerCase()).with(as(admin, "SUPPORT")))
                .andExpect(jsonPath("$.totalElements").value(2));
        mvc.perform(get("/api/admin/coupons").param("q", name).param("status", "PAUSED").with(opsAdmin()))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].id").value(pausedId));
        mvc.perform(get("/api/admin/coupons").param("q", name).param("status", "SCHEDULED").with(opsAdmin()))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].status").value("SCHEDULED"));
        mvc.perform(get("/api/admin/coupons").param("q", name).param("scope", "STORE").with(opsAdmin()))
                .andExpect(jsonPath("$.totalElements").value(0));
        mvc.perform(get("/api/admin/coupons").param("status", "NOPE").with(opsAdmin()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_PARAMETER"));
    }

    // ---------------------------------------------------------------- store owners

    @Test
    void aVendorsCouponIsAStoreCouponOfTheirOwnStore() throws Exception {
        UUID vendorId = UUID.randomUUID();
        createCoupon("/api/vendor/coupons", vendor(vendorId), flatBody(code("vend"), "\"public\":true"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.scope").value("STORE"))
                .andExpect(jsonPath("$.storeId").value(vendorId.toString()))
                .andExpect(jsonPath("$.storeType").value("GROCERY"))
                .andExpect(jsonPath("$.fundedBy").value("STORE"))
                .andExpect(jsonPath("$.createdBy").value(vendorUser(vendorId).toString()));
        // explicit STORE with their own id is fine too
        createCoupon("/api/vendor/coupons", vendor(vendorId), flatBody(code("vend"),
                "\"scope\":\"STORE\",\"storeId\":\"%s\",\"storeType\":\"GROCERY\"".formatted(vendorId)))
                .andExpect(status().isCreated());
    }

    @Test
    void aVendorCannotCreateAPlatformCouponOrOneForAnotherStore() throws Exception {
        UUID vendorId = UUID.randomUUID();
        createCoupon("/api/vendor/coupons", vendor(vendorId), flatBody(code("plat"), "\"scope\":\"PLATFORM\""))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("COUPON_SCOPE_NOT_ALLOWED"));
        createCoupon("/api/vendor/coupons", vendor(vendorId),
                flatBody(code("other"), "\"storeId\":\"%s\"".formatted(UUID.randomUUID())))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("COUPON_SCOPE_NOT_ALLOWED"));
        createCoupon("/api/vendor/coupons", vendor(vendorId), flatBody(code("type"), "\"storeType\":\"RESTAURANT\""))
                .andExpect(status().isForbidden());
        // not an approved vendor (no vendor_id claim), or not a vendor at all
        createCoupon("/api/vendor/coupons", as(UUID.randomUUID(), "VENDOR"), flatBody(code("nov"), ""))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("VENDOR_NOT_ACTIVE"));
        createCoupon("/api/vendor/coupons", restaurantOwner(UUID.randomUUID()), flatBody(code("rest"), ""))
                .andExpect(status().isForbidden());
    }

    @Test
    void vendorACannotSeeOrChangeVendorBsCoupons() throws Exception {
        UUID vendorA = UUID.randomUUID();
        UUID vendorB = UUID.randomUUID();
        String idA = body(createCoupon("/api/vendor/coupons", vendor(vendorA), flatBody(code("vena"), ""))
                .andExpect(status().isCreated())).get("id").asString();
        String idB = body(createCoupon("/api/vendor/coupons", vendor(vendorB), flatBody(code("venb"), ""))
                .andExpect(status().isCreated())).get("id").asString();
        String platformId = adminCoupon("\"code\":\"%s\",\"name\":\"P\",\"discountType\":\"FREE_DELIVERY\",%s"
                .formatted(code("plat"), validWindow())).get("id").asString();

        mvc.perform(get("/api/vendor/coupons").with(vendor(vendorA)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].id").value(idA));
        for (String foreign : List.of(idB, platformId)) {
            mvc.perform(get("/api/vendor/coupons/{id}", foreign).with(vendor(vendorA)))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.errorCode").value("COUPON_NOT_FOUND"));
            putJson("/api/vendor/coupons/" + foreign, vendor(vendorA), flatBody(code("hack"), ""))
                    .andExpect(status().isNotFound());
            mvc.perform(post("/api/vendor/coupons/{id}/pause", foreign).with(vendor(vendorA)))
                    .andExpect(status().isNotFound());
            mvc.perform(get("/api/vendor/coupons/{id}/usage", foreign).with(vendor(vendorA)))
                    .andExpect(status().isNotFound());
        }
        // B's coupon is unchanged and still active
        mvc.perform(get("/api/vendor/coupons/{id}", idB).with(vendor(vendorB)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(true));
        // the owner can edit and pause their own; admins see store coupons
        putJson("/api/vendor/coupons/" + idA, vendor(vendorA), flatBody(code("vena2"), "\"minOrderSubtotal\":99"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.minOrderSubtotal").value(99.00))
                .andExpect(jsonPath("$.scope").value("STORE"))
                .andExpect(jsonPath("$.storeId").value(vendorA.toString()));
        mvc.perform(post("/api/vendor/coupons/{id}/pause", idA).with(vendor(vendorA)))
                .andExpect(jsonPath("$.status").value("PAUSED"));
        mvc.perform(get("/api/admin/coupons/{id}", idA).with(as(admin, "SUPPORT")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.storeId").value(vendorA.toString()));
    }

    @Test
    void restaurantOwnersManageTheirRestaurantsCoupons() throws Exception {
        UUID restaurantId = UUID.randomUUID();
        String id = body(createCoupon("/api/restaurant/coupons", restaurantOwner(restaurantId),
                "{\"code\":\"%s\",\"name\":\"Free delivery\",\"discountType\":\"FREE_DELIVERY\",\"value\":5,%s}"
                        .formatted(code("rest"), validWindow()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.scope").value("STORE"))
                .andExpect(jsonPath("$.storeType").value("RESTAURANT"))
                .andExpect(jsonPath("$.storeId").value(restaurantId.toString()))
                .andExpect(jsonPath("$.value").isEmpty()))                       // ignored for FREE_DELIVERY
                .get("id").asString();
        mvc.perform(get("/api/restaurant/coupons").with(restaurantOwner(restaurantId)))
                .andExpect(jsonPath("$.totalElements").value(1));
        mvc.perform(get("/api/restaurant/coupons/{id}", id).with(restaurantOwner(UUID.randomUUID())))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/restaurant/coupons").with(as(UUID.randomUUID(), "RESTAURANT_OWNER")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("RESTAURANT_NOT_ACTIVE"));
        mvc.perform(get("/api/restaurant/coupons").with(vendor(UUID.randomUUID())))
                .andExpect(status().isForbidden());
    }

    // ---------------------------------------------------------------- customer offers

    @Test
    void customersSeeThePublicOffersUsableAtAStore() throws Exception {
        UUID vendorId = UUID.randomUUID();
        product(vendorId, "Rice");                                             // a vendor known from the catalog
        String suffix = UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        String anyStore = "ANY-" + suffix;
        String groceryOnly = "GRO-" + suffix;
        String restaurantOnly = "RES-" + suffix;
        String privateCode = "PRV-" + suffix;
        String paused = "PAU-" + suffix;
        String scheduled = "SCH-" + suffix;
        String own = "OWN-" + suffix;
        String otherStore = "OTH-" + suffix;
        String usedUp = "USE-" + suffix;
        adminCoupon(offer(anyStore, "\"storeType\":\"ANY\",\"public\":true"));
        adminCoupon(offer(groceryOnly, "\"storeType\":\"GROCERY\",\"public\":true,\"minOrderSubtotal\":199"));
        adminCoupon(offer(restaurantOnly, "\"storeType\":\"RESTAURANT\",\"public\":true"));
        adminCoupon(offer(privateCode, "\"public\":false"));
        adminCoupon(offer(paused, "\"public\":true,\"active\":false"));
        Instant now = Instant.now();
        adminCoupon("\"code\":\"%s\",\"name\":\"Soon\",\"discountType\":\"FREE_DELIVERY\",\"public\":true,\"validFrom\":\"%s\",\"validUntil\":\"%s\""
                .formatted(scheduled, now.plusSeconds(3600), now.plusSeconds(7200)));
        createCoupon("/api/vendor/coupons", vendor(vendorId), "{" + offer(own, "\"public\":true") + "}")
                .andExpect(status().isCreated());
        createCoupon("/api/vendor/coupons", vendor(UUID.randomUUID()), "{" + offer(otherStore, "\"public\":true") + "}")
                .andExpect(status().isCreated());
        adminCoupon(offer(usedUp, "\"public\":true,\"perCustomerLimit\":1"));

        UUID customerId = UUID.randomUUID();
        redeem(customerId, redemption(usedUp, UUID.randomUUID(), customerId, vendorId, "GROCERY", "300", "25"))
                .andExpect(status().isOk());

        List<String> codes = offerCodes(customerId, vendorId, null, suffix);
        assertThat(codes).containsExactlyInAnyOrder(anyStore, groceryOnly, own);

        JsonNode grocery = null;
        for (JsonNode node : body(mvc.perform(get("/api/coupons").param("storeId", vendorId.toString())
                .with(customer(customerId))))) {
            if (node.get("code").asString().equals(groceryOnly)) {
                grocery = node;
            }
        }
        assertThat(grocery).isNotNull();
        assertThat(grocery.get("name").asString()).isEqualTo("Offer");
        assertThat(grocery.get("discountType").asString()).isEqualTo("FLAT");
        assertThat(grocery.get("value").decimalValue()).isEqualByComparingTo("30.00");
        assertThat(grocery.get("minOrderSubtotal").decimalValue()).isEqualByComparingTo("199.00");
        assertThat(grocery.has("validUntil")).isTrue();
        assertThat(grocery.has("maxDiscount")).isTrue();

        // another customer can still use the used-up one
        assertThat(offerCodes(UUID.randomUUID(), vendorId, null, suffix)).contains(usedUp);
        // a restaurant (unknown to the catalog) gets the restaurant offers, as does an explicit storeType
        assertThat(offerCodes(customerId, UUID.randomUUID(), null, suffix)).containsExactlyInAnyOrder(anyStore,
                restaurantOnly);
        assertThat(offerCodes(customerId, vendorId, "RESTAURANT", suffix)).containsExactlyInAnyOrder(anyStore,
                restaurantOnly, own);
        // only customers
        mvc.perform(get("/api/coupons").param("storeId", vendorId.toString()).with(vendor(vendorId)))
                .andExpect(status().isForbidden());
    }

    private static String offer(String code, String extra) {
        return "\"code\":\"%s\",\"name\":\"Offer\",\"discountType\":\"FLAT\",\"value\":30,%s,%s"
                .formatted(code, validWindow(), extra);
    }

    private List<String> offerCodes(UUID customerId, UUID storeId, String storeType, String suffix) throws Exception {
        var request = get("/api/coupons").param("storeId", storeId.toString()).with(customer(customerId));
        if (storeType != null) {
            request = request.param("storeType", storeType);
        }
        JsonNode offers = body(mvc.perform(request).andExpect(status().isOk()));
        List<String> codes = new ArrayList<>();
        offers.forEach(node -> {
            String code = node.get("code").asString();
            if (code.endsWith(suffix)) {
                codes.add(code);
            }
        });
        return codes;
    }
}
