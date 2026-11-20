package com.grocery.apitests.api;

import com.grocery.apitests.support.Api;
import com.grocery.apitests.support.Await;
import com.grocery.apitests.support.Config;
import com.grocery.apitests.support.Res;
import com.grocery.apitests.support.World;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Map;

import static com.grocery.apitests.support.Json.obj;
import static org.assertj.core.api.Assertions.assertThat;

/** Phases 3–4: public search, vendor products, stock and prices (contract shapes, validation and conflicts). */
@Tag("api")
@Order(3)
@DisplayName("Catalog, inventory and pricing API")
class CatalogInventoryPricingApiTest {

    @Test
    void search_returns_a_page_with_facets_and_caps_the_page_size_at_50() {
        World.Store store = World.storeA();
        Res r = Await.until("rice searchable", Await.seconds(30),
                () -> Api.anon().get("/api/search/products?q=" + store.rice().name() + "&vendorId=" + store.vendorId()),
                res -> res.status() == 200 && res.body().contains(store.rice().id()));
        r.page();
        assertThat(r.map("facets")).containsKeys("categories", "brands", "foodTypes");
        Map<String, Object> card = r.<Map<String, Object>>list("content").stream()
                .filter(c -> store.rice().id().equals(c.get("id"))).findFirst().orElseThrow();
        assertThat(card).containsKeys("name", "unitLabel", "category", "vendor", "price", "inStock");
        assertThat(card.get("inStock")).isEqualTo(true);

        Api.anon().get("/api/search/products?size=500").ok().has("size", 50);
        Api.anon().get("/api/search/products?page=30&size=50").problem(400, "INVALID_PARAMETER");
        Api.anon().get("/api/search/products?foodType=PLASTIC").problem(400, "INVALID_PARAMETER");
        Api.anon().get("/api/search/products?minPrice=50&maxPrice=10").problem(400, "INVALID_PARAMETER");
        Api.anon().get("/api/search/restaurants?lat=12.9").problem(400, "INVALID_PARAMETER");
        Api.anon().get("/api/search/products?vendorId=not-a-uuid").problem(400, "INVALID_PARAMETER");
        Api.anon().get("/api/search/products?categoryId=xyz").problem(400, "INVALID_PARAMETER");
    }

    /**
     * The store directory (vendor-service) tells a guest whether a store delivers to a point (the cart's 15 km road
     * distance), and product search spans several stores with a repeated {@code vendorId}.
     */
    @Test
    void a_store_card_says_whether_it_delivers_to_a_point_and_search_spans_several_stores() {
        World.Store a = World.storeA();
        World.Store b = World.storeB();
        Res card = Api.anon().get("/api/stores/" + a.vendorId() + "?lat=" + World.HOME_LAT + "&lng=" + World.HOME_LNG)
                .ok().has("name", a.name()).has("city", "Hubballi").has("deliversHere", true);
        assertThat(card.dec("distanceKm")).isLessThan(new BigDecimal("2"));
        card.bodyDoesNotContain("gstin").bodyDoesNotContain("contactEmail").bodyDoesNotContain("ownerUserId");
        Api.anon().get("/api/stores/" + a.vendorId()).ok().has("distanceKm", null).has("deliversHere", null);
        // Chennai is ~600 km away: the card is shown, but the store does not deliver there
        Api.anon().get("/api/stores/" + a.vendorId() + "?lat=12.9202&lng=80.1445").ok().has("deliversHere", false);
        Api.anon().get("/api/stores/nearby?lat=" + World.HOME_LAT + "&lng=" + World.HOME_LNG).ok()
                .has("deliveryLimitKm", new BigDecimal("15.0"));

        Res both = Await.until("the products of both stores searchable", Await.seconds(30),
                () -> Api.anon().get("/api/search/products?size=50&vendorId=" + a.vendorId() + "&vendorId=" + b.vendorId()),
                res -> res.status() == 200 && res.body().contains(a.rice().id()) && res.body().contains(b.rice().id()));
        assertThat(both.<Map<String, Object>>list("content")).allSatisfy(product ->
                assertThat(((Map<?, ?>) product.get("vendor")).get("id")).isIn(a.vendorId(), b.vendorId()));
    }

    @Test
    void product_detail_has_price_and_stock_and_hidden_products_are_404() {
        World.Store store = World.storeA();
        Api.anon().get("/api/catalog/products/" + store.rice().id()).ok()
                .has("id", store.rice().id()).has("inStock", true)
                .has("price.sellingPrice", new BigDecimal("100.00")).has("price.mrp", new BigDecimal("120.00"))
                .has("price.discountPercent", 17).has("unitLabel", "1 kg");
        Api.anon().get("/api/catalog/restaurants/00000000-0000-7000-8000-000000000000")
                .problem(404, "RESTAURANT_NOT_FOUND");
    }

    @Test
    void vendor_product_validation_sku_conflict_and_draft_visibility() {
        World.Store store = World.storeA();
        Res invalid = Api.as(store.vendor()).body(obj("name", "", "categoryId", World.category(), "unitValue", -1,
                "unitOfMeasure", "KG")).post("/api/vendor/products").problem(400, "VALIDATION_FAILED");
        assertThat(invalid.<Map<String, String>>list("errors")).extracting(e -> e.get("field"))
                .contains("name", "unitValue");

        String sku = "APIT-" + Config.RUN.toUpperCase();
        Res draft = Api.as(store.vendor()).body(obj("name", "Apit Draft " + Config.TAG, "categoryId", World.category(),
                "unitValue", 500, "unitOfMeasure", "G", "sku", sku)).post("/api/vendor/products").created()
                .has("status", "DRAFT").has("unitLabel", "500 g").has("vendorId", store.vendorId());
        Api.as(store.vendor()).body(obj("name", "Apit Draft 2", "categoryId", World.category(), "unitValue", 1,
                "unitOfMeasure", "KG", "sku", sku)).post("/api/vendor/products").problem(409, "SKU_ALREADY_EXISTS");
        Api.anon().get("/api/catalog/products/" + draft.str("id")).problem(404, "PRODUCT_NOT_FOUND");
        Api.as(store.vendor()).delete("/api/vendor/products/" + draft.str("id")).expect(204);
        Api.as(store.vendor()).post("/api/vendor/products/" + draft.str("id") + "/publish")
                .expect(409);
    }

    @Test
    void stock_rules_insufficient_stock_and_invalid_filter() {
        World.Store store = World.storeA();
        Res stock = Api.as(store.vendor()).get("/api/vendor/inventory/" + store.dal().id()).ok();
        assertThat(stock.map("")).containsKeys("productId", "onHand", "reserved", "available", "lowStock", "inStock");
        int available = stock.integer("available");
        Res tooMuch = Api.as(store.vendor()).body(obj("type", "DECREASE", "quantity", Math.min(available + 1, 100000),
                "reason", "DAMAGED")).post("/api/vendor/inventory/" + store.dal().id() + "/adjustments");
        tooMuch.problem(409, "INSUFFICIENT_STOCK");
        assertThat(tooMuch.map("")).containsKeys("available", "reserved");
        Api.as(store.vendor()).body(obj("type", "INCREASE", "quantity", 0, "reason", "RESTOCK"))
                .post("/api/vendor/inventory/" + store.dal().id() + "/adjustments").problem(400, "VALIDATION_FAILED");
        Api.as(store.vendor()).get("/api/vendor/inventory?filter=SOMETIMES").problem(400, "INVALID_PARAMETER");
        Api.as(store.vendor()).get("/api/vendor/inventory?size=5").ok().page();
        assertThat(Api.as(store.vendor()).get("/api/vendor/inventory/summary").ok().map(""))
                .containsKeys("products", "inStock", "lowStock", "outOfStock");
    }

    @Test
    void price_rules_threshold_approval_and_double_decision() {
        World.Store store = World.storeA();
        World.Product product = World.createProduct(store.vendor(), "Apit Pricing " + Config.TAG, "100.00", "80.00", 5);
        String path = "/api/vendor/prices/" + product.id();
        Res above = Api.as(store.vendor()).body(obj("mrp", new BigDecimal("50.00"), "sellingPrice", new BigDecimal("60.00")))
                .put(path).problem(422, "SELLING_PRICE_ABOVE_MRP");
        assertThat(above.body()).contains("sellingPrice");

        Api.as(store.vendor()).body(obj("mrp", new BigDecimal("100.00"), "sellingPrice", new BigDecimal("80.00")))
                .put(path).problem(409, "PRICE_UNCHANGED");
        Api.as(store.vendor()).body(obj("mrp", new BigDecimal("100.00"), "sellingPrice", new BigDecimal("75.00")))
                .put(path).ok().has("outcome", "APPLIED").has("price.sellingPrice", new BigDecimal("75.00"));

        Res pending = Api.as(store.vendor()).body(obj("mrp", new BigDecimal("100.00"),
                "sellingPrice", new BigDecimal("30.00"), "note", "Clearance")).put(path).expect(202)
                .has("outcome", "PENDING_APPROVAL").has("price.sellingPrice", new BigDecimal("75.00"));
        String requestId = pending.str("pendingRequest.id");
        assertThat(requestId).isNotBlank();
        Api.as(store.vendor()).body(obj("mrp", new BigDecimal("100.00"), "sellingPrice", new BigDecimal("70.00")))
                .put(path).problem(409, "PRICE_CHANGE_PENDING");

        Api.as(World.adminWithRole("FINANCE_ADMIN")).body(obj()).post("/api/admin/price-change-requests/" + requestId + "/reject")
                .problem(400, "VALIDATION_FAILED");
        Api.as(World.adminWithRole("FINANCE_ADMIN")).body(obj("note", "ok"))
                .post("/api/admin/price-change-requests/" + requestId + "/approve").ok().has("status", "APPROVED");
        Api.as(World.adminWithRole("OPS_ADMIN")).body(obj("note", "again"))
                .post("/api/admin/price-change-requests/" + requestId + "/approve").problem(409, "PRICE_CHANGE_NOT_PENDING");
        Api.as(store.vendor()).get(path).ok().has("price.sellingPrice", new BigDecimal("30.00"));
        Api.as(store.vendor()).get(path + "/history").ok().page();
    }
}
