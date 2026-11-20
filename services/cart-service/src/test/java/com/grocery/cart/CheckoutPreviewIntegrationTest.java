package com.grocery.cart;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Checkout preview (contract §3). Addresses are placed due north of the store, so the straight-line distance is
 * exact: road distance = 1.3 × km, 1 decimal. Grocery: 3 km included, ₹25 + ₹8 per started extra km, free from
 * ₹499, max 15 km, minimum ₹99. Restaurant: 3 km, ₹30 + ₹10, never free, max 12 km, no minimum.
 */
class CheckoutPreviewIntegrationTest extends AbstractIntegrationTest {

    private UUID groceryCart(UUID customerId, String sellingPrice, int quantity) throws Exception {
        UUID vendorId = vendor("Green Basket");
        UUID productId = product(vendorId, "Basmati Rice", "120.00", sellingPrice, 50);
        addProduct(customerId, productId, quantity).andExpect(status().isOk());
        return vendorId;
    }

    @Test
    void happyPathUsesTheDefaultAddress() throws Exception {
        UUID customerId = UUID.randomUUID();
        groceryCart(customerId, "90.00", 2);
        address(customerId, 9.0, false);
        UUID home = address(customerId, 2.0, true);                       // 2.6 km: within the 3 included km

        preview(customerId, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.store.name").value("Green Basket"))
                // Phase 9: the pickup point, from the vendor store the cart already reads
                .andExpect(jsonPath("$.store.city").value("Bengaluru"))
                .andExpect(jsonPath("$.store.latitude").value(STORE_LAT))
                .andExpect(jsonPath("$.store.longitude").value(STORE_LNG))
                .andExpect(jsonPath("$.lines.length()").value(1))
                .andExpect(jsonPath("$.itemCount").value(2))
                .andExpect(jsonPath("$.subtotal").value(180.00))
                .andExpect(jsonPath("$.mrpTotal").value(240.00))
                .andExpect(jsonPath("$.savings").value(60.00))
                .andExpect(jsonPath("$.currency").value("INR"))
                .andExpect(jsonPath("$.address.id").value(home.toString()))
                .andExpect(jsonPath("$.address.label").value("Home"))
                .andExpect(jsonPath("$.address.recipientName").value("Asha Rao"))
                .andExpect(jsonPath("$.address.postalCode").value("560001"))
                .andExpect(jsonPath("$.address.latitude").isNumber())
                .andExpect(jsonPath("$.address.isDefault").doesNotExist())
                .andExpect(jsonPath("$.delivery.distanceKm").value(2.6))
                .andExpect(jsonPath("$.delivery.fee").value(25.00))
                .andExpect(jsonPath("$.delivery.freeAbove").value(499.00))
                .andExpect(jsonPath("$.delivery.maxDistanceKm").value(15))
                .andExpect(jsonPath("$.delivery.minimumOrder").value(99.00))
                .andExpect(jsonPath("$.total").value(205.00))
                .andExpect(jsonPath("$.issues.length()").value(0))
                .andExpect(jsonPath("$.canCheckout").value(true))
                .andExpect(jsonPath("$.pricedAt").isNotEmpty())
                .andExpect(jsonPath("$.updatedAt").isNotEmpty());

        // the address was read with the customer's own token
        assertThat(STUB.calls("/api/customers/me/addresses")).allSatisfy(call ->
                assertThat(call.authorization()).isEqualTo("Bearer token-" + customerId));
    }

    @Test
    void explicitAddressAndFeeMaths() throws Exception {
        UUID customerId = UUID.randomUUID();
        groceryCart(customerId, "90.00", 2);                              // subtotal 180
        address(customerId, 2.0, true);
        UUID office = address(customerId, 3.4, false);                    // 4.4 km: 2 started extra km
        UUID farAway = address(customerId, 10.0, false);                  // 13.0 km: 10 extra km

        preview(customerId, office)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.address.id").value(office.toString()))
                .andExpect(jsonPath("$.delivery.distanceKm").value(4.4))
                .andExpect(jsonPath("$.delivery.fee").value(41.00))      // 25 + 2 × 8
                .andExpect(jsonPath("$.total").value(221.00))
                .andExpect(jsonPath("$.canCheckout").value(true));

        preview(customerId, farAway)
                .andExpect(jsonPath("$.delivery.distanceKm").value(13.0))
                .andExpect(jsonPath("$.delivery.fee").value(105.00))     // 25 + 10 × 8
                .andExpect(jsonPath("$.total").value(285.00))
                .andExpect(jsonPath("$.canCheckout").value(true));
    }

    @Test
    void deliveryIsFreeFromTheThreshold() throws Exception {
        UUID customerId = UUID.randomUUID();
        UUID vendorId = vendor("Green Basket");
        UUID ghee = product(vendorId, "Ghee", "550.00", "499.00", 5);
        addProduct(customerId, ghee, 1).andExpect(status().isOk());       // exactly the threshold
        UUID office = address(customerId, 3.4, true);

        preview(customerId, office)
                .andExpect(jsonPath("$.subtotal").value(499.00))
                .andExpect(jsonPath("$.delivery.distanceKm").value(4.4))
                .andExpect(jsonPath("$.delivery.fee").value(0.00))
                .andExpect(jsonPath("$.total").value(499.00))
                .andExpect(jsonPath("$.canCheckout").value(true));
    }

    @Test
    void outOfDeliveryArea() throws Exception {
        UUID customerId = UUID.randomUUID();
        groceryCart(customerId, "90.00", 2);
        UUID tooFar = address(customerId, 12.0, true);                    // 15.6 km > 15

        preview(customerId, tooFar)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.delivery.distanceKm").value(15.6))
                .andExpect(jsonPath("$.issues[0].code").value("OUT_OF_DELIVERY_AREA"))
                .andExpect(jsonPath("$.issues[0].blocking").value(true))
                .andExpect(jsonPath("$.issues[0].message")
                        .value("This address is 15.6 km away; Green Basket delivers up to 15 km"))
                .andExpect(jsonPath("$.canCheckout").value(false));
    }

    @Test
    void belowMinimumOrder() throws Exception {
        UUID customerId = UUID.randomUUID();
        groceryCart(customerId, "55.00", 1);
        address(customerId, 2.0, true);

        preview(customerId, null)
                .andExpect(jsonPath("$.subtotal").value(55.00))
                .andExpect(jsonPath("$.delivery.fee").value(25.00))
                .andExpect(jsonPath("$.total").value(80.00))
                .andExpect(jsonPath("$.issues[0].code").value("BELOW_MINIMUM_ORDER"))
                .andExpect(jsonPath("$.issues[0].message")
                        .value("Add ₹44.00 more to reach the minimum order of ₹99.00"))
                .andExpect(jsonPath("$.canCheckout").value(false));
    }

    @Test
    void addressWithoutLocation() throws Exception {
        UUID customerId = UUID.randomUUID();
        groceryCart(customerId, "90.00", 2);
        UUID unpinned = address(customerId, null, true);

        preview(customerId, null)
                .andExpect(jsonPath("$.address.id").value(unpinned.toString()))
                .andExpect(jsonPath("$.address.latitude").isEmpty())
                .andExpect(jsonPath("$.delivery").isEmpty())
                .andExpect(jsonPath("$.total").value(180.00))
                .andExpect(jsonPath("$.issues[0].code").value("ADDRESS_LOCATION_MISSING"))
                .andExpect(jsonPath("$.canCheckout").value(false));
    }

    @Test
    void noAddressAtAll() throws Exception {
        UUID customerId = UUID.randomUUID();
        groceryCart(customerId, "90.00", 2);

        preview(customerId, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.address").isEmpty())
                .andExpect(jsonPath("$.delivery").isEmpty())
                .andExpect(jsonPath("$.total").value(180.00))
                .andExpect(jsonPath("$.issues.length()").value(1))
                .andExpect(jsonPath("$.issues[0].code").value("ADDRESS_REQUIRED"))
                .andExpect(jsonPath("$.canCheckout").value(false));
    }

    @Test
    void someoneElsesAddressIsNotFound() throws Exception {
        UUID customerId = UUID.randomUUID();
        groceryCart(customerId, "90.00", 2);
        UUID othersAddress = address(UUID.randomUUID(), 2.0, true);

        preview(customerId, othersAddress)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("ADDRESS_NOT_FOUND"));
    }

    @Test
    void emptyCart() throws Exception {
        UUID customerId = UUID.randomUUID();
        address(customerId, 2.0, true);

        preview(customerId, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.store").isEmpty())
                .andExpect(jsonPath("$.address").isNotEmpty())
                .andExpect(jsonPath("$.delivery").isEmpty())
                .andExpect(jsonPath("$.total").value(0.00))
                .andExpect(jsonPath("$.issues[0].code").value("EMPTY_CART"))
                .andExpect(jsonPath("$.canCheckout").value(false));
    }

    @Test
    void restaurantDeliveryRules() throws Exception {
        UUID customerId = UUID.randomUUID();
        UUID spiceRoute = restaurant("Spice Route");
        UUID biryani = menuItem(spiceRoute, "Chicken Biryani", "249.00");
        addMenuItem(customerId, spiceRoute, biryani, 3).andExpect(status().isOk());   // 747: never free
        UUID office = address(customerId, 3.4, true);
        UUID farAway = address(customerId, 10.0, false);                               // 13.0 km > 12

        preview(customerId, office)
                .andExpect(jsonPath("$.store.type").value("RESTAURANT"))
                .andExpect(jsonPath("$.store.city").value("Bengaluru"))          // from the menu quote
                .andExpect(jsonPath("$.store.latitude").value(STORE_LAT))
                .andExpect(jsonPath("$.store.longitude").value(STORE_LNG))
                .andExpect(jsonPath("$.subtotal").value(747.00))
                .andExpect(jsonPath("$.delivery.distanceKm").value(4.4))
                .andExpect(jsonPath("$.delivery.fee").value(50.00))   // 30 + 2 × 10
                .andExpect(jsonPath("$.delivery.freeAbove").isEmpty())
                .andExpect(jsonPath("$.delivery.maxDistanceKm").value(12))
                .andExpect(jsonPath("$.delivery.minimumOrder").isEmpty())
                .andExpect(jsonPath("$.total").value(797.00))
                .andExpect(jsonPath("$.canCheckout").value(true));

        preview(customerId, farAway)
                .andExpect(jsonPath("$.issues[*].code").value(hasItem("OUT_OF_DELIVERY_AREA")))
                .andExpect(jsonPath("$.canCheckout").value(false));
    }

    @Test
    void priceChangeAloneStillAllowsCheckoutButLiveProblemsBlockIt() throws Exception {
        UUID customerId = UUID.randomUUID();
        UUID vendorId = vendor("Green Basket");
        UUID rice = product(vendorId, "Basmati Rice", "120.00", "90.00", 50);
        addProduct(customerId, rice, 2).andExpect(status().isOk());
        address(customerId, 2.0, true);

        setPrice(rice, "120.00", "95.00");
        preview(customerId, null)
                .andExpect(jsonPath("$.issues[0].code").value("PRICE_CHANGED"))
                .andExpect(jsonPath("$.subtotal").value(190.00))
                .andExpect(jsonPath("$.total").value(215.00))
                .andExpect(jsonPath("$.canCheckout").value(true));

        setStock(rice, 1);
        preview(customerId, null)
                .andExpect(jsonPath("$.issues[0].code").value("INSUFFICIENT_STOCK"))
                .andExpect(jsonPath("$.canCheckout").value(false));
    }

    @Test
    void storeWithoutLocationOrUnreachable() throws Exception {
        UUID customerId = UUID.randomUUID();
        UUID vendorId = groceryCart(customerId, "90.00", 2);
        address(customerId, 2.0, true);

        STUB.vendors.computeIfPresent(vendorId, (id, v) -> new StubServices.Vendor(id, v.name(), true, null, null));
        preview(customerId, null)
                .andExpect(jsonPath("$.delivery").isEmpty())
                .andExpect(jsonPath("$.store.city").value("Bengaluru"))
                .andExpect(jsonPath("$.store.latitude").isEmpty())                // no location set: null, not 0
                .andExpect(jsonPath("$.store.longitude").isEmpty())
                .andExpect(jsonPath("$.issues[0].code").value("STORE_UNAVAILABLE"))
                .andExpect(jsonPath("$.canCheckout").value(false));

        STUB.down.add("vendor");
        preview(customerId, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.store.name").value("Green Basket"))
                .andExpect(jsonPath("$.store.city").isEmpty())                    // the vendor could not be reached
                .andExpect(jsonPath("$.store.latitude").isEmpty())
                .andExpect(jsonPath("$.store.longitude").isEmpty())
                .andExpect(jsonPath("$.delivery").isEmpty())
                .andExpect(jsonPath("$.issues[0].code").value("AVAILABILITY_UNKNOWN"))
                .andExpect(jsonPath("$.issues[*].code").value(not(hasItem("STORE_UNAVAILABLE"))))
                .andExpect(jsonPath("$.canCheckout").value(false));
    }

    @Test
    void customerServiceDownFailsThePreview() throws Exception {
        UUID customerId = UUID.randomUUID();
        groceryCart(customerId, "90.00", 2);
        STUB.down.add("customer");

        preview(customerId, null)
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.errorCode").value("DEPENDENCY_UNAVAILABLE"))
                .andExpect(jsonPath("$.dependency").value("customer"));
    }
}
