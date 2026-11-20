package com.grocery.inventory.contract;

import au.com.dius.pact.provider.junit5.PactVerificationContext;
import au.com.dius.pact.provider.junitsupport.Provider;
import au.com.dius.pact.provider.junitsupport.State;
import au.com.dius.pact.provider.junitsupport.loader.PactFolder;
import au.com.dius.pact.provider.spring.spring7.PactVerificationSpring7Provider;
import au.com.dius.pact.provider.spring.spring7.Spring7MockMvcTestTarget;
import com.grocery.inventory.AbstractIntegrationTest;
import com.grocery.inventory.entity.MovementType;
import com.grocery.inventory.entity.ProductStatus;
import com.grocery.inventory.entity.StockItem;
import com.grocery.inventory.repository.StockItemRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.TestTemplate;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;

/**
 * inventory-service as a provider: verifies every pact in {@code contracts/pacts} whose provider is inventory-service
 * (today cart-service's {@code POST /internal/inventory/availability}) against this service's Spring context through
 * MockMvc. The provider states stub {@link StockItemRepository} only, so the real security chain, request validation,
 * controller, {@code AvailabilityService} (available = on hand − reserved, missing ids) and Jackson all run.
 *
 * <p>Auth: {@code /internal/**} accepts any authenticated caller (callers forward the customer's token). The pacts
 * carry an opaque example bearer token; the {@link JwtDecoder} is a Mockito bean that turns any bearer token into a
 * CUSTOMER JWT, so no real signed token is needed. Token validation itself is covered by the security tests.
 */
@Provider("inventory-service")
@PactFolder("../../contracts/pacts")
class InventoryServiceProviderPactTest extends AbstractIntegrationTest {

    private static final UUID CALLER_ID = UUID.fromString("0193f1a2-5b6c-7d8e-9f01-a2b3c4d5e601");
    private static final Instant NOW = Instant.parse("2026-11-14T09:00:00Z");

    @MockitoBean
    StockItemRepository stockItems;
    @MockitoBean
    JwtDecoder jwtDecoder;

    @BeforeEach
    void target(PactVerificationContext context) {
        context.setTarget(new Spring7MockMvcTestTarget(mvc));
        given(jwtDecoder.decode(anyString())).willAnswer(call -> customerJwt(call.getArgument(0), CALLER_ID));
    }

    @TestTemplate
    @ExtendWith(PactVerificationSpring7Provider.class)
    void verifyPact(PactVerificationContext context) {
        context.verifyInteraction();
    }

    // ------------------------------------------------------------------------------------------------ states

    @State("a product has 12 units available and another product is unknown")
    void stockedAndUnknownProduct(Map<String, Object> params) {
        UUID productId = uuid(params, "productId");
        UUID missingProductId = uuid(params, "missingProductId");
        StockItem stock = StockItem.empty(productId, uuid(params, "vendorId"), NOW);
        stock.adjust(MovementType.INCREASE, 12, NOW);
        // the repository knows only the stocked product; the unknown one is simply absent (→ missing)
        given(stockItems.findAvailable(argThat((Collection<UUID> ids) ->
                ids != null && ids.containsAll(List.of(productId, missingProductId))), eq(ProductStatus.ARCHIVED)))
                .willReturn(List.of(stock));
    }

    // ------------------------------------------------------------------------------------------------ helpers

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
}
