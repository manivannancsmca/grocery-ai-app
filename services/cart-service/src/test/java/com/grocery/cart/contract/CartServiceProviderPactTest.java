package com.grocery.cart.contract;

import au.com.dius.pact.provider.junit5.PactVerificationContext;
import au.com.dius.pact.provider.junitsupport.Provider;
import au.com.dius.pact.provider.junitsupport.State;
import au.com.dius.pact.provider.junitsupport.loader.PactFolder;
import au.com.dius.pact.provider.spring.spring7.PactVerificationSpring7Provider;
import au.com.dius.pact.provider.spring.spring7.Spring7MockMvcTestTarget;
import com.grocery.cart.AbstractIntegrationTest;
import com.grocery.cart.client.Dependency;
import com.grocery.cart.dto.CartDtos.AddressView;
import com.grocery.cart.dto.CartDtos.CartLine;
import com.grocery.cart.dto.CartDtos.CheckoutPreview;
import com.grocery.cart.dto.CartDtos.CouponView;
import com.grocery.cart.dto.CartDtos.DeliveryView;
import com.grocery.cart.dto.CartDtos.Issue;
import com.grocery.cart.dto.CartDtos.StoreView;
import com.grocery.cart.model.ItemType;
import com.grocery.cart.model.StoreType;
import com.grocery.cart.service.CartService;
import com.grocery.cart.web.CartErrors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.TestTemplate;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;

/**
 * cart-service as a provider: verifies every pact in {@code contracts/pacts} whose provider is cart-service
 * (today order-service's checkout preview) against this service's Spring context through MockMvc — the real
 * security filter chain, {@code @PreAuthorize}, controller, Jackson and grocery-common's ProblemDetail handler run;
 * only {@link CartService} is stubbed by the provider states (its Redis store and the six services it reads are not
 * part of this contract).
 *
 * <p>Auth: the pacts carry an opaque example bearer token. The {@link JwtDecoder} is a Mockito bean that turns any
 * bearer token into a CUSTOMER JWT whose subject is the provider state's {@code customerId} — "me" of
 * {@code /api/cart/**} — so no real signed token is needed. Signature, issuer and audience validation are covered by
 * the security tests, not by contracts.
 */
@Provider("cart-service")
@PactFolder("../../contracts/pacts")
class CartServiceProviderPactTest extends AbstractIntegrationTest {

    private static final Instant UPDATED_AT = Instant.parse("2026-11-14T09:30:00.123Z");
    private static final UUID VENDOR_ID = UUID.fromString("0193f1a2-5b6c-7d8e-9f01-a2b3c4d5e6b1");
    private static final UUID PRODUCT_ID = UUID.fromString("0193f1a2-5b6c-7d8e-9f01-a2b3c4d5e6c1");

    @MockitoBean
    CartService carts;
    @MockitoBean
    JwtDecoder jwtDecoder;

    /** The subject of every bearer token: the customer of the current provider state. */
    private UUID customerId = UUID.randomUUID();

    @BeforeEach
    void target(PactVerificationContext context) {
        context.setTarget(new Spring7MockMvcTestTarget(mvc));
        given(jwtDecoder.decode(anyString())).willAnswer(call -> customerJwt(call.getArgument(0), customerId));
    }

    @TestTemplate
    @ExtendWith(PactVerificationSpring7Provider.class)
    void verifyPact(PactVerificationContext context) {
        context.verifyInteraction();
    }

    // ------------------------------------------------------------------------------------------------ states

    @State("a grocery cart ready for checkout, with coupon SAVE10 applied")
    void readyWithCoupon(Map<String, Object> params) {
        customerId = uuid(params, "customerId");
        UUID addressId = uuid(params, "addressId");
        CouponView coupon = new CouponView("SAVE10", true, money("36.00"), money("0.00"), null, null, null);
        given(carts.preview(customerId, addressId))
                .willReturn(ready(addressId, coupon, money("36.00"), money("349.00")));
    }

    @State("a grocery cart ready for checkout at the customer's default address")
    void readyAtDefaultAddress(Map<String, Object> params) {
        customerId = uuid(params, "customerId");
        given(carts.preview(customerId, null))
                .willReturn(ready(uuid(params, "addressId"), null, money("0.00"), money("385.00")));
    }

    @State("a grocery cart with an item that went out of stock")
    void outOfStock(Map<String, Object> params) {
        customerId = uuid(params, "customerId");
        UUID addressId = uuid(params, "addressId");
        UUID productId = uuid(params, "productId");
        CheckoutPreview ready = ready(addressId, null, money("0.00"), money("385.00"));
        String message = "Amul Taaza Toned Milk 1 L is out of stock";
        CartLine line = new CartLine(productId, ItemType.PRODUCT, "Amul Taaza Toned Milk 1 L", "1 L", null, "VEG", 4,
                money("90.00"), money("100.00"), money("360.00"), money("90.00"), 0, "OUT_OF_STOCK");
        given(carts.preview(customerId, addressId)).willReturn(new CheckoutPreview(ready.store(), List.of(line),
                ready.itemCount(), ready.subtotal(), ready.mrpTotal(), ready.savings(), ready.currency(),
                List.of(new Issue("OUT_OF_STOCK", productId, true, message)), ready.updatedAt(), ready.address(),
                ready.delivery(), null, money("0.00"), money("0.00"), ready.total(), false, ready.pricedAt()));
    }

    @State("the address is not one of the customer's")
    void unknownAddress(Map<String, Object> params) {
        customerId = uuid(params, "customerId");
        given(carts.preview(customerId, uuid(params, "addressId"))).willThrow(CartErrors.addressNotFound());
    }

    @State("customer-service is unavailable")
    void customerServiceDown(Map<String, Object> params) {
        customerId = uuid(params, "customerId");
        given(carts.preview(customerId, uuid(params, "addressId")))
                .willThrow(CartErrors.dependencyUnavailable(Dependency.CUSTOMER));
    }

    // ------------------------------------------------------------------------------------------------ data

    /** A priced, checkout-ready grocery cart: 4 × 90.00 (MRP 100.00), delivery 25.00 for 1.2 km. */
    private static CheckoutPreview ready(UUID addressId, CouponView coupon, BigDecimal discount, BigDecimal total) {
        StoreView store = new StoreView(VENDOR_ID, StoreType.GROCERY, "FreshMart Indiranagar", "Bengaluru", 12.9719,
                77.6412);
        CartLine line = new CartLine(PRODUCT_ID, ItemType.PRODUCT, "Amul Taaza Toned Milk 1 L", "1 L",
                "https://cdn.grocery.local/products/amul-taaza-1l.jpg", "VEG", 4, money("90.00"), money("100.00"),
                money("360.00"), money("90.00"), 12, null);
        AddressView address = new AddressView(addressId, "Home", "Asha Rao", "+919800000001",
                "12, 4th Cross, HAL 2nd Stage", "Indiranagar", "Near the metro station", "Bengaluru", "Karnataka",
                "560038", 12.9784, 77.6408);
        DeliveryView delivery = new DeliveryView(money("1.20"), money("25.00"), money("499.00"), money("15.00"),
                money("99.00"));
        return new CheckoutPreview(store, List.of(line), 4, money("360.00"), money("400.00"), money("40.00"), "INR",
                List.of(), UPDATED_AT, address, delivery, coupon, discount, money("0.00"), total, true,
                UPDATED_AT.plusSeconds(5));
    }

    private static Jwt customerJwt(String token, UUID subject) {
        Instant now = Instant.now();
        return Jwt.withTokenValue(token)
                .header("alg", "none")
                .subject(subject.toString())
                .claim("roles", List.of("CUSTOMER"))
                .claim("email", "contract-test@example.com")
                .issuedAt(now)
                .expiresAt(now.plusSeconds(300))
                .build();
    }

    private static UUID uuid(Map<String, Object> params, String name) {
        return UUID.fromString(String.valueOf(params.get(name)));
    }

    private static BigDecimal money(String amount) {
        return new BigDecimal(amount);
    }
}
