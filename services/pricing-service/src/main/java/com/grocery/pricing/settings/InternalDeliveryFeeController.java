package com.grocery.pricing.settings;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The delivery fees in force for cart-service's checkout preview (internal: {@code /internal/**} is never routed by
 * the gateway). Any authenticated caller: cart-service forwards the customer's token, which is validated here (zero
 * trust); the fees are what every customer sees at checkout anyway.
 */
@RestController
public class InternalDeliveryFeeController {

    private final DeliveryFeeSettingsService fees;

    public InternalDeliveryFeeController(DeliveryFeeSettingsService fees) {
        this.fees = fees;
    }

    @GetMapping("/internal/delivery-fees")
    public DeliveryFeeSettings current() {
        return fees.current();
    }
}
