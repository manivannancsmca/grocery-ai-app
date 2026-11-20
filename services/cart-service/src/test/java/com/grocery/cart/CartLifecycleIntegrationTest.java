package com.grocery.cart;

import com.grocery.cart.store.CartStore;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import tools.jackson.databind.JsonNode;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Adding, merging, changing, removing and clearing; persistence, isolation, TTL, store conflicts and limits. */
class CartLifecycleIntegrationTest extends AbstractIntegrationTest {

    @Test
    void emptyCartHasNoStoreAndNoLines() throws Exception {
        getCart(UUID.randomUUID())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.store").isEmpty())
                .andExpect(jsonPath("$.lines.length()").value(0))
                .andExpect(jsonPath("$.itemCount").value(0))
                .andExpect(jsonPath("$.subtotal").value(0.00))
                .andExpect(jsonPath("$.currency").value("INR"))
                .andExpect(jsonPath("$.issues.length()").value(0));
        assertThat(STUB.calls).isEmpty();               // nothing to price
    }

    @Test
    void addMergeUpdateRemoveAndClear() throws Exception {
        UUID customerId = UUID.randomUUID();
        UUID vendorId = vendor("Green Basket");
        UUID rice = product(vendorId, "Basmati Rice", "120.00", "90.00", 10);
        UUID dal = product(vendorId, "Toor Dal", "160.00", "150.00", 10);

        addProduct(customerId, rice, 2)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.store.id").value(vendorId.toString()))
                .andExpect(jsonPath("$.store.type").value("GROCERY"))
                .andExpect(jsonPath("$.store.name").value("Green Basket"))
                .andExpect(jsonPath("$.store.city").value("Bengaluru"))
                .andExpect(jsonPath("$.store.latitude").value(STORE_LAT))
                .andExpect(jsonPath("$.store.longitude").value(STORE_LNG))
                .andExpect(jsonPath("$.lines[0].itemId").value(rice.toString()))
                .andExpect(jsonPath("$.lines[0].type").value("PRODUCT"))
                .andExpect(jsonPath("$.lines[0].name").value("Basmati Rice"))
                .andExpect(jsonPath("$.lines[0].unitLabel").value("1 kg"))
                .andExpect(jsonPath("$.lines[0].quantity").value(2))
                .andExpect(jsonPath("$.lines[0].unitPrice").value(90.00))
                .andExpect(jsonPath("$.lines[0].mrp").value(120.00))
                .andExpect(jsonPath("$.lines[0].lineTotal").value(180.00))
                .andExpect(jsonPath("$.lines[0].priceWhenAdded").value(90.00))
                .andExpect(jsonPath("$.lines[0].availableQuantity").value(10))
                .andExpect(jsonPath("$.lines[0].issue").isEmpty())
                .andExpect(jsonPath("$.updatedAt").isNotEmpty());

        // the same product again: quantities merge into one line
        addProduct(customerId, rice, 3)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lines.length()").value(1))
                .andExpect(jsonPath("$.lines[0].quantity").value(5));

        addProduct(customerId, dal, 1)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lines.length()").value(2))
                .andExpect(jsonPath("$.lines[1].itemId").value(dal.toString()))
                .andExpect(jsonPath("$.itemCount").value(6))
                .andExpect(jsonPath("$.subtotal").value(600.00))         // 5 × 90 + 150
                .andExpect(jsonPath("$.mrpTotal").value(760.00))         // 5 × 120 + 160
                .andExpect(jsonPath("$.savings").value(160.00));

        setQuantity(customerId, rice, 4)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lines[0].quantity").value(4))
                .andExpect(jsonPath("$.itemCount").value(5));

        removeItem(customerId, dal)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lines.length()").value(1));
        removeItem(customerId, dal)                                     // idempotent
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lines.length()").value(1));

        setQuantity(customerId, rice, 0)                                 // 0 removes the line
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lines.length()").value(0))
                .andExpect(jsonPath("$.store").isEmpty());

        addProduct(customerId, dal, 2).andExpect(status().isOk());
        clearCart(customerId).andExpect(status().isNoContent());
        getCart(customerId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.store").isEmpty())
                .andExpect(jsonPath("$.lines.length()").value(0));
    }

    @Test
    void cartPersistsAcrossRequestsAndIsIsolatedPerCustomer() throws Exception {
        UUID asha = UUID.randomUUID();
        UUID ravi = UUID.randomUUID();
        UUID vendorId = vendor("Green Basket");
        UUID rice = product(vendorId, "Basmati Rice", "120.00", "90.00", 10);
        UUID atta = product(vendorId, "Atta", "60.00", "55.00", 10);

        addProduct(asha, rice, 2).andExpect(status().isOk());
        addProduct(ravi, atta, 1).andExpect(status().isOk());

        getCart(asha)
                .andExpect(jsonPath("$.lines.length()").value(1))
                .andExpect(jsonPath("$.lines[0].itemId").value(rice.toString()))
                .andExpect(jsonPath("$.lines[0].quantity").value(2));
        getCart(ravi)
                .andExpect(jsonPath("$.lines.length()").value(1))
                .andExpect(jsonPath("$.lines[0].itemId").value(atta.toString()));

        // one hash per customer (the JWT subject): one write so far → version 1
        assertThat(redis.opsForHash().get(CartStore.key(asha), "version")).isEqualTo("1");
        assertThat((String) redis.opsForHash().get(CartStore.key(asha), "data")).contains(rice.toString());

        clearCart(asha).andExpect(status().isNoContent());
        getCart(ravi).andExpect(jsonPath("$.lines.length()").value(1));
    }

    @Test
    void everyWriteSetsA30DayTtl() throws Exception {
        UUID customerId = UUID.randomUUID();
        UUID vendorId = vendor("Green Basket");
        UUID rice = product(vendorId, "Basmati Rice", "120.00", "90.00", 10);

        addProduct(customerId, rice, 1).andExpect(status().isOk());
        long thirtyDays = Duration.ofDays(30).toSeconds();
        assertThat(ttlSeconds(customerId)).isBetween(thirtyDays - 60, thirtyDays);

        redis.expire(CartStore.key(customerId), Duration.ofDays(1));      // pretend time passed
        setQuantity(customerId, rice, 2).andExpect(status().isOk());
        assertThat(ttlSeconds(customerId)).isBetween(thirtyDays - 60, thirtyDays);

        redis.expire(CartStore.key(customerId), Duration.ofDays(1));
        clearCart(customerId).andExpect(status().isNoContent());           // an emptied cart is written too
        assertThat(ttlSeconds(customerId)).isBetween(thirtyDays - 60, thirtyDays);
    }

    @Test
    void anotherStoreConflictsUntilReplaceCart() throws Exception {
        UUID customerId = UUID.randomUUID();
        UUID greenBasket = vendor("Green Basket");
        UUID freshMart = vendor("Fresh Mart");
        UUID rice = product(greenBasket, "Basmati Rice", "120.00", "90.00", 10);
        UUID milk = product(freshMart, "Milk", "30.00", "28.00", 10);
        UUID spiceRoute = restaurant("Spice Route");
        UUID biryani = menuItem(spiceRoute, "Chicken Biryani", "249.00");

        addProduct(customerId, rice, 2).andExpect(status().isOk());

        addProduct(customerId, milk, 1)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("CART_STORE_CONFLICT"))
                .andExpect(jsonPath("$.currentStoreId").value(greenBasket.toString()))
                .andExpect(jsonPath("$.currentStoreName").value("Green Basket"))
                .andExpect(jsonPath("$.currentStoreType").value("GROCERY"));
        addMenuItem(customerId, spiceRoute, biryani, 1)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("CART_STORE_CONFLICT"))
                .andExpect(jsonPath("$.currentStoreType").value("GROCERY"));
        getCart(customerId)
                .andExpect(jsonPath("$.lines.length()").value(1))
                .andExpect(jsonPath("$.lines[0].itemId").value(rice.toString()));

        addProductReplacing(customerId, milk, 1)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.store.id").value(freshMart.toString()))
                .andExpect(jsonPath("$.store.name").value("Fresh Mart"))
                .andExpect(jsonPath("$.lines.length()").value(1))
                .andExpect(jsonPath("$.lines[0].itemId").value(milk.toString()));

        postJson("/api/cart/items", customerId, Map.of("type", "MENU_ITEM", "restaurantId", spiceRoute,
                "menuItemId", biryani, "quantity", 2, "replaceCart", true))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.store.id").value(spiceRoute.toString()))
                .andExpect(jsonPath("$.store.type").value("RESTAURANT"))
                .andExpect(jsonPath("$.store.name").value("Spice Route"))
                .andExpect(jsonPath("$.store.city").value("Bengaluru"))
                .andExpect(jsonPath("$.store.latitude").value(STORE_LAT))
                .andExpect(jsonPath("$.store.longitude").value(STORE_LNG))
                .andExpect(jsonPath("$.lines[0].type").value("MENU_ITEM"))
                .andExpect(jsonPath("$.lines[0].quantity").value(2))
                .andExpect(jsonPath("$.lines[0].unitPrice").value(249.00))
                .andExpect(jsonPath("$.lines[0].mrp").isEmpty())
                .andExpect(jsonPath("$.lines[0].availableQuantity").isEmpty())
                .andExpect(jsonPath("$.lines[0].foodType").value("NON_VEG"))
                .andExpect(jsonPath("$.subtotal").value(498.00))
                .andExpect(jsonPath("$.mrpTotal").value(498.00))
                .andExpect(jsonPath("$.savings").value(0.00));
    }

    @Test
    void quantityAndLineLimits() throws Exception {
        UUID customerId = UUID.randomUUID();
        UUID vendorId = vendor("Green Basket");
        UUID rice = product(vendorId, "Basmati Rice", "120.00", "90.00", 100);

        addProduct(customerId, rice, 21)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errorCode").value("QUANTITY_LIMIT"));
        addProduct(customerId, rice, 15).andExpect(status().isOk());
        addProduct(customerId, rice, 6)                                     // the line would be 21
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errorCode").value("QUANTITY_LIMIT"));
        addProduct(customerId, rice, 5)                                     // exactly 20 is fine
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lines[0].quantity").value(20));
        setQuantity(customerId, rice, 21)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errorCode").value("QUANTITY_LIMIT"));

        List<UUID> more = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            more.add(product(vendorId, "Item " + i, "10.00", "10.00", 5));
        }
        for (int i = 0; i < 29; i++) {                                      // 30 lines with the rice
            addProduct(customerId, more.get(i), 1).andExpect(status().isOk());
        }
        addProduct(customerId, more.get(29), 1)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errorCode").value("CART_LIMIT_REACHED"));
        addProduct(customerId, more.get(0), 1)                              // an existing line still grows
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lines.length()").value(30));
    }

    @Test
    void invalidRequestsAndUnknownLines() throws Exception {
        UUID customerId = UUID.randomUUID();
        postJson("/api/cart/items", customerId, Map.of("type", "PRODUCT", "quantity", 1))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].field").value("productId"));
        postJson("/api/cart/items", customerId, Map.of("type", "MENU_ITEM", "menuItemId", UUID.randomUUID(),
                "quantity", 1))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("restaurantId"));
        postJson("/api/cart/items", customerId, Map.of("type", "PRODUCT", "productId", UUID.randomUUID(),
                "menuItemId", UUID.randomUUID(), "quantity", 1))
                .andExpect(status().isBadRequest());
        postJson("/api/cart/items", customerId, Map.of("type", "PRODUCT", "productId", UUID.randomUUID(), "quantity", 0))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"));
        setQuantity(customerId, UUID.randomUUID(), -1).andExpect(status().isBadRequest());

        setQuantity(customerId, UUID.randomUUID(), 2)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("CART_ITEM_NOT_FOUND"));
        setQuantity(customerId, UUID.randomUUID(), 0)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("CART_ITEM_NOT_FOUND"));
        assertThat(STUB.calls).isEmpty();
    }

    @Test
    void onlyCustomersHaveACart() throws Exception {
        UUID userId = UUID.randomUUID();
        mvc.perform(get("/api/cart")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/cart/preview")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/cart").with(as(userId, "VENDOR"))).andExpect(status().isForbidden());
        mvc.perform(get("/api/cart").with(as(userId, "OPS_ADMIN"))).andExpect(status().isForbidden());
        mvc.perform(post("/api/cart/items").with(as(userId, "RESTAURANT_OWNER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"PRODUCT\",\"productId\":\"" + UUID.randomUUID() + "\",\"quantity\":1}"))
                .andExpect(status().isForbidden());
        assertThat(STUB.calls).isEmpty();
    }

    @Test
    void healthIsPublic() throws Exception {
        JsonNode health = json.readTree(mvc.perform(get("/actuator/health"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(health.get("status").asString()).isEqualTo("UP");
    }
}
