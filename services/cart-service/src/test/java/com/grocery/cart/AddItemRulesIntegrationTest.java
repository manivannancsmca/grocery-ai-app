package com.grocery.cart;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** An item must be orderable right now, otherwise nothing changes (contract §2). */
class AddItemRulesIntegrationTest extends AbstractIntegrationTest {

    @Test
    void hiddenUnknownOrUnpricedProductsAreUnavailable() throws Exception {
        UUID customerId = UUID.randomUUID();
        UUID vendorId = vendor("Green Basket");
        UUID hidden = product(vendorId, "Hidden Rice", "120.00", "90.00", 10);
        setVisible(hidden, false);
        UUID unpriced = product(vendorId, "New Oil", null, null, 10);

        addProduct(customerId, hidden, 1)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("ITEM_UNAVAILABLE"));
        addProduct(customerId, UUID.randomUUID(), 1)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("ITEM_UNAVAILABLE"));
        addProduct(customerId, unpriced, 1)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("ITEM_UNAVAILABLE"));
        getCart(customerId).andExpect(jsonPath("$.lines.length()").value(0));
        assertThat(redis.hasKey("cart:v1:" + customerId)).isFalse();
    }

    @Test
    void stockIsCheckedForTheNewLineQuantity() throws Exception {
        UUID customerId = UUID.randomUUID();
        UUID vendorId = vendor("Green Basket");
        UUID soldOut = product(vendorId, "Mangoes", "300.00", "250.00", 0);
        UUID noStockRecord = product(vendorId, "Kiwi", "100.00", "90.00", null);
        UUID rice = product(vendorId, "Basmati Rice", "120.00", "90.00", 5);

        addProduct(customerId, soldOut, 1)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("OUT_OF_STOCK"));
        addProduct(customerId, noStockRecord, 1)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("OUT_OF_STOCK"));

        addProduct(customerId, rice, 6)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("INSUFFICIENT_STOCK"))
                .andExpect(jsonPath("$.available").value(5));
        addProduct(customerId, rice, 3).andExpect(status().isOk());
        addProduct(customerId, rice, 3)                                    // 3 + 3 > 5
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("INSUFFICIENT_STOCK"))
                .andExpect(jsonPath("$.available").value(5));
        setQuantity(customerId, rice, 6)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("INSUFFICIENT_STOCK"))
                .andExpect(jsonPath("$.available").value(5));
        addProduct(customerId, rice, 2)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lines[0].quantity").value(5));
    }

    @Test
    void restaurantMustBeApprovedOpenAndAcceptingAndTheItemOnItsMenu() throws Exception {
        UUID customerId = UUID.randomUUID();
        UUID spiceRoute = restaurant("Spice Route");
        UUID biryani = menuItem(spiceRoute, "Chicken Biryani", "249.00");
        UUID otherRestaurant = restaurant("Dosa Point");
        UUID dosa = menuItem(otherRestaurant, "Masala Dosa", "90.00");

        addMenuItem(customerId, spiceRoute, dosa, 1)                        // not on this restaurant's menu
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("ITEM_UNAVAILABLE"));
        addMenuItem(customerId, UUID.randomUUID(), biryani, 1)              // unknown restaurant
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("ITEM_UNAVAILABLE"));

        setMenuItem(spiceRoute, biryani, "249.00", false);
        addMenuItem(customerId, spiceRoute, biryani, 1)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("ITEM_UNAVAILABLE"));
        setMenuItem(spiceRoute, biryani, "249.00", true);

        setRestaurant(spiceRoute, "SUSPENDED", true, true);
        addMenuItem(customerId, spiceRoute, biryani, 1)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("ITEM_UNAVAILABLE"));

        setRestaurant(spiceRoute, "APPROVED", true, false);
        addMenuItem(customerId, spiceRoute, biryani, 1)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("STORE_CLOSED"));

        setRestaurant(spiceRoute, "APPROVED", false, true);
        addMenuItem(customerId, spiceRoute, biryani, 1)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("NOT_ACCEPTING_ORDERS"));

        getCart(customerId).andExpect(jsonPath("$.lines.length()").value(0));

        setRestaurant(spiceRoute, "APPROVED", true, true);
        addMenuItem(customerId, spiceRoute, biryani, 2)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lines[0].itemId").value(biryani.toString()))
                .andExpect(jsonPath("$.lines[0].name").value("Chicken Biryani"))
                .andExpect(jsonPath("$.lines[0].quantity").value(2));
        // one menu-quote call for the check and the response
        assertThat(STUB.calls("/internal/restaurants/" + spiceRoute)).hasSize(6);
    }

    @Test
    void addingFailsWith503WhenADependencyIsDown() throws Exception {
        UUID customerId = UUID.randomUUID();
        UUID vendorId = vendor("Green Basket");
        UUID rice = product(vendorId, "Basmati Rice", "120.00", "90.00", 5);
        UUID spiceRoute = restaurant("Spice Route");
        UUID biryani = menuItem(spiceRoute, "Chicken Biryani", "249.00");

        for (String dependency : new String[]{"pricing", "inventory", "catalog"}) {
            STUB.down.add(dependency);
            addProduct(customerId, rice, 1)
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(jsonPath("$.errorCode").value("DEPENDENCY_UNAVAILABLE"))
                    .andExpect(jsonPath("$.dependency").value(dependency));
            STUB.down.remove(dependency);
        }
        STUB.down.add("restaurant");
        addMenuItem(customerId, spiceRoute, biryani, 1)
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.errorCode").value("DEPENDENCY_UNAVAILABLE"));
        STUB.down.clear();
        getCart(customerId).andExpect(jsonPath("$.lines.length()").value(0));

        resetBreakers();
        STUB.slow.add("pricing");                                          // a timeout counts as down
        addProduct(customerId, rice, 1)
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.errorCode").value("DEPENDENCY_UNAVAILABLE"));
        STUB.slow.clear();

        resetBreakers();
        // the vendor's store is only needed to show the cart: adding still works without it
        STUB.down.add("vendor");
        addProduct(customerId, rice, 1)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lines[0].quantity").value(1))
                .andExpect(jsonPath("$.issues[0].code").value("AVAILABILITY_UNKNOWN"))
                .andExpect(jsonPath("$.issues[0].itemId").isEmpty())
                .andExpect(jsonPath("$.issues[0].blocking").value(true));
    }
}
