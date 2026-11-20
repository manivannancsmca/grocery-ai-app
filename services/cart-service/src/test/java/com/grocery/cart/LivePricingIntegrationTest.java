package com.grocery.cart;

import com.grocery.cart.StubServices.Call;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Everything that can change is re-checked live every time the cart is shown (contract §2, issue codes). */
class LivePricingIntegrationTest extends AbstractIntegrationTest {

    @Test
    void priceChangeIsReportedButDoesNotBlock() throws Exception {
        UUID customerId = UUID.randomUUID();
        UUID vendorId = vendor("Green Basket");
        UUID rice = product(vendorId, "Basmati Rice", "120.00", "90.00", 10);
        addProduct(customerId, rice, 2).andExpect(status().isOk());

        setPrice(rice, "120.00", "50.00");
        getCart(customerId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lines[0].unitPrice").value(50.00))
                .andExpect(jsonPath("$.lines[0].priceWhenAdded").value(90.00))
                .andExpect(jsonPath("$.lines[0].lineTotal").value(100.00))
                .andExpect(jsonPath("$.lines[0].issue").value("PRICE_CHANGED"))
                .andExpect(jsonPath("$.subtotal").value(100.00))
                .andExpect(jsonPath("$.savings").value(140.00))
                .andExpect(jsonPath("$.issues.length()").value(1))
                .andExpect(jsonPath("$.issues[0].code").value("PRICE_CHANGED"))
                .andExpect(jsonPath("$.issues[0].itemId").value(rice.toString()))
                .andExpect(jsonPath("$.issues[0].blocking").value(false))
                .andExpect(jsonPath("$.issues[0].message").value("Basmati Rice now costs ₹50.00 (was ₹90.00)"));

        // changing the quantity takes the live price as the new priceWhenAdded
        setQuantity(customerId, rice, 3)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lines[0].priceWhenAdded").value(50.00))
                .andExpect(jsonPath("$.lines[0].issue").isEmpty())
                .andExpect(jsonPath("$.issues.length()").value(0));
    }

    @Test
    void stockAndVisibilityProblemsAfterTheFactBlock() throws Exception {
        UUID customerId = UUID.randomUUID();
        UUID vendorId = vendor("Green Basket");
        UUID rice = product(vendorId, "Basmati Rice", "120.00", "90.00", 10);
        UUID dal = product(vendorId, "Toor Dal", "160.00", "150.00", 10);
        UUID oil = product(vendorId, "Sunflower Oil", "200.00", "180.00", 10);
        UUID ghee = product(vendorId, "Ghee", "600.00", "550.00", 10);
        addProduct(customerId, rice, 4).andExpect(status().isOk());
        addProduct(customerId, dal, 1).andExpect(status().isOk());
        addProduct(customerId, oil, 1).andExpect(status().isOk());
        addProduct(customerId, ghee, 1).andExpect(status().isOk());

        setStock(rice, 3);                    // INSUFFICIENT_STOCK
        setStock(dal, 0);                     // OUT_OF_STOCK
        setVisible(oil, false);               // ITEM_UNAVAILABLE (hidden)
        setPrice(ghee, null, null);           // ITEM_UNAVAILABLE (no live price)

        getCart(customerId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lines[0].issue").value("INSUFFICIENT_STOCK"))
                .andExpect(jsonPath("$.lines[0].availableQuantity").value(3))
                .andExpect(jsonPath("$.lines[0].unitPrice").value(90.00))
                .andExpect(jsonPath("$.lines[1].issue").value("OUT_OF_STOCK"))
                .andExpect(jsonPath("$.lines[1].availableQuantity").value(0))
                .andExpect(jsonPath("$.lines[2].issue").value("ITEM_UNAVAILABLE"))
                .andExpect(jsonPath("$.lines[2].unitPrice").isEmpty())
                .andExpect(jsonPath("$.lines[2].lineTotal").isEmpty())
                .andExpect(jsonPath("$.lines[2].name").value("Sunflower Oil"))   // stored snapshot
                .andExpect(jsonPath("$.lines[3].issue").value("ITEM_UNAVAILABLE"))
                .andExpect(jsonPath("$.lines[3].unitPrice").isEmpty())
                .andExpect(jsonPath("$.subtotal").value(510.00))               // 4 × 90 + 150; unpriced excluded
                .andExpect(jsonPath("$.itemCount").value(7))
                .andExpect(jsonPath("$.issues.length()").value(4))
                .andExpect(jsonPath("$.issues[0].message").value("Only 3 of Basmati Rice left in stock (you have 4)"))
                .andExpect(jsonPath("$.issues[*].blocking").value(org.hamcrest.Matchers.everyItem(
                        org.hamcrest.Matchers.is(true))));
    }

    @Test
    void suspendedVendorMakesTheStoreUnavailable() throws Exception {
        UUID customerId = UUID.randomUUID();
        UUID vendorId = vendor("Green Basket");
        UUID rice = product(vendorId, "Basmati Rice", "120.00", "90.00", 10);
        addProduct(customerId, rice, 1).andExpect(status().isOk());

        STUB.vendors.computeIfPresent(vendorId,
                (id, v) -> new StubServices.Vendor(id, v.name(), false, v.latitude(), v.longitude()));
        getCart(customerId)
                .andExpect(jsonPath("$.issues[0].code").value("STORE_UNAVAILABLE"))
                .andExpect(jsonPath("$.issues[0].itemId").isEmpty())
                .andExpect(jsonPath("$.issues[0].blocking").value(true));

        STUB.vendors.remove(vendorId);                                     // 404 VENDOR_NOT_FOUND
        getCart(customerId).andExpect(jsonPath("$.issues[0].code").value("STORE_UNAVAILABLE"));
    }

    @Test
    void restaurantChangesAfterTheFact() throws Exception {
        UUID customerId = UUID.randomUUID();
        UUID spiceRoute = restaurant("Spice Route");
        UUID biryani = menuItem(spiceRoute, "Chicken Biryani", "249.00");
        UUID kebab = menuItem(spiceRoute, "Seekh Kebab", "199.00");
        addMenuItem(customerId, spiceRoute, biryani, 1).andExpect(status().isOk());
        addMenuItem(customerId, spiceRoute, kebab, 2).andExpect(status().isOk());

        setMenuItem(spiceRoute, biryani, "269.00", true);
        setMenuItem(spiceRoute, kebab, "199.00", false);
        setRestaurant(spiceRoute, "APPROVED", false, false);
        getCart(customerId)
                .andExpect(jsonPath("$.store.type").value("RESTAURANT"))
                .andExpect(jsonPath("$.issues[0].code").value("STORE_CLOSED"))
                .andExpect(jsonPath("$.issues[1].code").value("NOT_ACCEPTING_ORDERS"))
                .andExpect(jsonPath("$.lines[0].issue").value("PRICE_CHANGED"))
                .andExpect(jsonPath("$.lines[0].unitPrice").value(269.00))
                .andExpect(jsonPath("$.lines[1].issue").value("ITEM_UNAVAILABLE"))
                .andExpect(jsonPath("$.lines[1].unitPrice").isEmpty())
                .andExpect(jsonPath("$.subtotal").value(269.00));

        setRestaurant(spiceRoute, "SUSPENDED", true, true);
        getCart(customerId).andExpect(jsonPath("$.issues[0].code").value("STORE_UNAVAILABLE"));
    }

    @Test
    void cartIsStillShownWhenDependenciesAreDown() throws Exception {
        UUID customerId = UUID.randomUUID();
        UUID vendorId = vendor("Green Basket");
        UUID rice = product(vendorId, "Basmati Rice", "120.00", "90.00", 10);
        addProduct(customerId, rice, 2).andExpect(status().isOk());

        STUB.down.add("pricing");
        getCart(customerId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lines[0].name").value("Basmati Rice"))
                .andExpect(jsonPath("$.lines[0].quantity").value(2))
                .andExpect(jsonPath("$.lines[0].unitPrice").isEmpty())
                .andExpect(jsonPath("$.lines[0].lineTotal").isEmpty())
                .andExpect(jsonPath("$.lines[0].priceWhenAdded").value(90.00))
                .andExpect(jsonPath("$.lines[0].issue").value("PRICING_UNAVAILABLE"))
                .andExpect(jsonPath("$.issues[0].blocking").value(true))
                .andExpect(jsonPath("$.subtotal").value(0.00));
        STUB.down.clear();

        STUB.down.add("inventory");
        getCart(customerId)
                .andExpect(jsonPath("$.lines[0].unitPrice").value(90.00))
                .andExpect(jsonPath("$.lines[0].availableQuantity").isEmpty())
                .andExpect(jsonPath("$.lines[0].issue").value("AVAILABILITY_UNKNOWN"));
        STUB.down.clear();

        STUB.down.add("catalog");
        getCart(customerId)
                .andExpect(jsonPath("$.lines[0].name").value("Basmati Rice"))       // stored snapshot
                .andExpect(jsonPath("$.lines[0].issue").value("AVAILABILITY_UNKNOWN"));
        STUB.down.clear();

        // a restaurant cart with restaurant-service down
        UUID other = UUID.randomUUID();
        UUID spiceRoute = restaurant("Spice Route");
        UUID biryani = menuItem(spiceRoute, "Chicken Biryani", "249.00");
        addMenuItem(other, spiceRoute, biryani, 1).andExpect(status().isOk());
        STUB.down.add("restaurant");
        getCart(other)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.store.name").value("Spice Route"))
                .andExpect(jsonPath("$.lines[0].name").value("Chicken Biryani"))
                .andExpect(jsonPath("$.lines[0].unitPrice").isEmpty())
                .andExpect(jsonPath("$.lines[0].issue").value("PRICING_UNAVAILABLE"));
    }

    @Test
    void oneBatchCallPerDependencyCarryingTheCallersToken() throws Exception {
        UUID customerId = UUID.randomUUID();
        UUID vendorId = vendor("Green Basket");
        UUID rice = product(vendorId, "Basmati Rice", "120.00", "90.00", 10);
        UUID dal = product(vendorId, "Toor Dal", "160.00", "150.00", 10);
        UUID oil = product(vendorId, "Sunflower Oil", "200.00", "180.00", 10);
        addProduct(customerId, rice, 1).andExpect(status().isOk());
        addProduct(customerId, dal, 1).andExpect(status().isOk());

        STUB.calls.clear();
        addProduct(customerId, oil, 1).andExpect(status().isOk());       // check + response: still one each
        assertThat(STUB.calls("/internal/catalog/products/lookup")).hasSize(1);
        assertThat(STUB.calls("/internal/prices/quote")).hasSize(1);
        assertThat(STUB.calls("/internal/inventory/availability")).hasSize(1);
        assertThat(STUB.calls("/internal/vendors/" + vendorId + "/store")).hasSize(1);
        assertThat(STUB.calls("/internal/prices/quote").getFirst().body().get("productIds")).hasSize(3);

        STUB.calls.clear();
        mvc.perform(get("/api/cart").with(customer(customerId)).header("X-Correlation-Id", "cart-test-0001"))
                .andExpect(status().isOk());
        assertThat(STUB.calls).hasSize(4);
        List<String> paths = STUB.calls.stream().map(Call::path).toList();
        assertThat(paths).containsExactlyInAnyOrder("/internal/catalog/products/lookup", "/internal/prices/quote",
                "/internal/inventory/availability", "/internal/vendors/" + vendorId + "/store");
        for (Call call : STUB.calls) {
            assertThat(call.authorization()).isEqualTo("Bearer token-" + customerId);
            assertThat(call.correlationId()).isEqualTo("cart-test-0001");
        }
        List<String> lookedUp = new ArrayList<>();
        STUB.calls("/internal/catalog/products/lookup").getFirst().body().get("productIds")
                .forEach(id -> lookedUp.add(id.asString()));
        assertThat(lookedUp).containsExactly(rice.toString(), dal.toString(), oil.toString());
    }

    @Test
    void circuitBreakerStopsCallingADeadDependency() throws Exception {
        UUID customerId = UUID.randomUUID();
        UUID vendorId = vendor("Green Basket");
        UUID rice = product(vendorId, "Basmati Rice", "120.00", "90.00", 10);
        addProduct(customerId, rice, 1).andExpect(status().isOk());
        resetBreakers();
        STUB.calls.clear();

        STUB.down.add("pricing");
        for (int i = 0; i < 6; i++) {
            getCart(customerId)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.lines[0].issue").value("PRICING_UNAVAILABLE"));
        }
        // window of 4 calls, all failed → open: calls 5 and 6 never left the cart service
        assertThat(STUB.calls("/internal/prices/quote")).hasSize(4);
        assertThat(STUB.calls("/internal/inventory/availability")).hasSize(6);
    }
}
