package com.grocery.pricing.settings;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The delivery fees in force (the minimum order, free delivery above, base fees…), as admins set them in the
 * platform settings. Any authenticated caller; the token is validated here (zero trust). The fees are what every
 * customer sees at checkout anyway.
 * <ul>
 *   <li>{@code GET /internal/delivery-fees}: cart-service's checkout preview (internal: {@code /internal/**} is never
 *       routed by the gateway; cart-service forwards the customer's token).</li>
 *   <li>{@code GET /api/pricing/delivery-fees}: the same answer through the gateway, for the assistants' getStoreRules
 *       tool, so "what is the minimum order?" is answered with the live values (docs/rag/RAG-MCP-INTEGRATION.md).</li>
 * </ul>
 */
@RestController
public class InternalDeliveryFeeController {

    private final DeliveryFeeSettingsService fees;

    public InternalDeliveryFeeController(DeliveryFeeSettingsService fees) {
        this.fees = fees;
    }

    @GetMapping({"/internal/delivery-fees", "/api/pricing/delivery-fees"})
    public DeliveryFeeSettings current() {
        return fees.current();
    }
}
