package com.grocery.order.client;

import com.grocery.order.config.OrderProperties;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.UUID;

/**
 * cart-service: the caller's live checkout preview ({@code GET /api/cart/preview?addressId=}), read with the
 * customer's own token — "the cart" is always the token subject's cart, so a customer can never order from another
 * customer's cart or address.
 */
@Component
public class CartClient {

    static final String ADDRESS_NOT_FOUND = "ADDRESS_NOT_FOUND";

    private final Downstream downstream;
    private final RestClient client;

    public CartClient(Downstream downstream, OrderProperties properties) {
        this.downstream = downstream;
        this.client = downstream.client(properties.getServices().getCart());
    }

    /**
     * The preview for {@code addressId} (null = the customer's default address). NotFound when the address does
     * not exist or belongs to someone else (cart passes customer-service's 404 {@code ADDRESS_NOT_FOUND} on).
     */
    public Outcome<CartPreview> preview(CallContext context, UUID addressId) {
        return downstream.call(Dependency.CART, ADDRESS_NOT_FOUND, () -> client.get()
                .uri(builder -> {
                    builder.path("/api/cart/preview");
                    if (addressId != null) {
                        builder.queryParam("addressId", addressId);
                    }
                    return builder.build();
                })
                .headers(context::applyTo)
                .retrieve()
                .body(CartPreview.class));
    }
}
