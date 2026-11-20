package com.grocery.cart;

import com.grocery.cart.client.CallContext;
import com.grocery.cart.client.PricingClient;
import com.grocery.cart.config.CartProperties;
import com.grocery.cart.service.DeliveryFeeRules;
import com.grocery.cart.service.DeliveryFeeRules.Rules;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The delivery fees of the admin platform settings (docs/api/platform-settings.md, area delivery-fees): the preview
 * charges what pricing-service says, asks it at most once per interval, keeps the last answer while it cannot be
 * reached and charges the configured fees before its first answer. Checkout never breaks because of this. Addresses
 * are due north of the store: 2.0 km → 2.6 km by road (within the 3 included km).
 */
class DeliveryFeeSettingsIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private DeliveryFeeRules fees;
    @Autowired
    private PricingClient pricing;
    @Autowired
    private CartProperties properties;

    /** Leaves the configured fees (read from the stub) in force for the other tests. */
    @AfterEach
    void configuredFeesAgain() {
        STUB.reset();
        fees.expire();
        fees.current(new CallContext("Bearer token-" + UUID.randomUUID(), null));
    }

    private UUID groceryCartAt2Km(UUID customerId) throws Exception {
        UUID vendorId = vendor("Green Basket");
        UUID rice = product(vendorId, "Basmati Rice", "120.00", "90.00", 50);
        addProduct(customerId, rice, 2).andExpect(status().isOk());              // subtotal 180.00
        return address(customerId, 2.0, true);
    }

    @Test
    void thePreviewChargesTheFeesInForceAtPricingService() throws Exception {
        UUID customerId = UUID.randomUUID();
        UUID home = groceryCartAt2Km(customerId);
        STUB.deliveryFees.put("groceryBaseFee", new BigDecimal("40.00"));
        STUB.deliveryFees.put("groceryFreeAbove", new BigDecimal("999.00"));
        STUB.deliveryFees.put("groceryMinimumOrder", new BigDecimal("200.00"));
        fees.expire();

        preview(customerId, home)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.delivery.distanceKm").value(2.6))
                .andExpect(jsonPath("$.delivery.fee").value(40.00))
                .andExpect(jsonPath("$.delivery.freeAbove").value(999.00))
                .andExpect(jsonPath("$.delivery.minimumOrder").value(200.00))
                .andExpect(jsonPath("$.delivery.maxDistanceKm").value(15))           // always the cart's config
                .andExpect(jsonPath("$.total").value(220.00))
                .andExpect(jsonPath("$.issues[0].code").value("BELOW_MINIMUM_ORDER"))
                .andExpect(jsonPath("$.canCheckout").value(false));
        // asked with the customer's own token, once for the interval
        preview(customerId, home).andExpect(status().isOk());
        assertThat(STUB.calls("/internal/delivery-fees")).hasSize(1).allSatisfy(call -> {
            assertThat(call.method()).isEqualTo("GET");
            assertThat(call.authorization()).isEqualTo("Bearer token-" + customerId);
        });
    }

    @Test
    void whilePricingServiceCannotBeReachedTheLastFeesItGaveStay() throws Exception {
        UUID customerId = UUID.randomUUID();
        UUID home = groceryCartAt2Km(customerId);
        STUB.deliveryFees.put("groceryBaseFee", new BigDecimal("40.00"));
        fees.expire();
        preview(customerId, home).andExpect(jsonPath("$.delivery.fee").value(40.00));

        STUB.down.add("pricing-fees");
        STUB.deliveryFees.put("groceryBaseFee", new BigDecimal("55.00"));
        fees.expire();
        preview(customerId, home)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.delivery.fee").value(40.00))
                .andExpect(jsonPath("$.issues.length()").value(0))
                .andExpect(jsonPath("$.canCheckout").value(true));

        STUB.down.remove("pricing-fees");
        fees.expire();
        preview(customerId, home).andExpect(jsonPath("$.delivery.fee").value(55.00));
    }

    @Test
    void beforePricingServiceHasAnsweredTheConfiguredFeesApply() {
        DeliveryFeeRules fresh = new DeliveryFeeRules(pricing, properties);
        CallContext call = new CallContext("Bearer token-" + UUID.randomUUID(), null);
        STUB.deliveryFees.put("groceryBaseFee", new BigDecimal("40.00"));
        STUB.down.add("pricing-fees");

        Rules rules = fresh.current(call);
        assertThat(rules.grocery().getBaseFee()).isEqualByComparingTo("25.00");
        assertThat(rules.grocery().getMinimumOrder()).isEqualByComparingTo("99.00");
        assertThat(rules.restaurant().getBaseFee()).isEqualByComparingTo("30.00");
        // a failed lookup is not repeated within the interval
        fresh.current(call);
        assertThat(STUB.calls("/internal/delivery-fees")).hasSize(1);

        STUB.down.remove("pricing-fees");
        fresh.expire();
        rules = fresh.current(call);
        assertThat(rules.grocery().getBaseFee()).isEqualByComparingTo("40.00");
        assertThat(rules.grocery().getMaxDistanceKm()).isEqualByComparingTo("15");         // from config
        assertThat(rules.restaurant().getFreeAbove()).isNull();                             // never free
    }
}
