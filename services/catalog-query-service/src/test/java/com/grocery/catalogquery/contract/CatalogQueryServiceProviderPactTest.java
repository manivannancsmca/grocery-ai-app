package com.grocery.catalogquery.contract;

import au.com.dius.pact.provider.junit5.PactVerificationContext;
import au.com.dius.pact.provider.junitsupport.Provider;
import au.com.dius.pact.provider.junitsupport.State;
import au.com.dius.pact.provider.junitsupport.loader.PactFolder;
import au.com.dius.pact.provider.spring.spring7.PactVerificationSpring7Provider;
import au.com.dius.pact.provider.spring.spring7.Spring7MockMvcTestTarget;
import com.grocery.catalogquery.AbstractIntegrationTest;
import com.grocery.catalogquery.dto.CatalogDtos.Price;
import com.grocery.catalogquery.dto.CatalogDtos.ProductCard;
import com.grocery.catalogquery.dto.CatalogDtos.ProductLookupResponse;
import com.grocery.catalogquery.dto.CatalogDtos.Ref;
import com.grocery.catalogquery.service.ProductSearchService;
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
 * catalog-query-service as a provider: verifies every pact in {@code contracts/pacts} whose provider is
 * catalog-query-service (today cart-service's {@code POST /internal/catalog/products/lookup}) against this service's
 * Spring context through MockMvc. The real security chain (these paths are NOT public), request validation,
 * controller and Jackson run; the provider states stub {@link ProductSearchService} — its Elasticsearch read model
 * is not part of the contract — with the cards and missing ids it would answer.
 *
 * <p>Auth: {@code /internal/**} accepts any authenticated caller (callers forward the customer's token). The pacts
 * carry an opaque example bearer token; the {@link JwtDecoder} is a Mockito bean that turns any bearer token into a
 * CUSTOMER JWT, so no real signed token is needed. Token validation itself is covered by the security tests.
 */
@Provider("catalog-query-service")
@PactFolder("../../contracts/pacts")
class CatalogQueryServiceProviderPactTest extends AbstractIntegrationTest {

    private static final UUID CALLER_ID = UUID.fromString("0193f1a2-5b6c-7d8e-9f01-a2b3c4d5e601");

    @MockitoBean
    ProductSearchService products;
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

    @State("one product is visible and another is not")
    void visibleAndHiddenProduct(Map<String, Object> params) {
        UUID productId = uuid(params, "productId");
        UUID missingProductId = uuid(params, "missingProductId");
        ProductCard card = new ProductCard(productId.toString(), "Amul Taaza Toned Milk 1 L", "1 L", "VEG",
                "https://cdn.grocery.local/products/amul-taaza-1l.jpg",
                new Ref("0193f1a2-5b6c-7d8e-9f01-a2b3c4d5e801", "Amul"),
                new Ref("0193f1a2-5b6c-7d8e-9f01-a2b3c4d5e901", "Milk"),
                new Ref(String.valueOf(params.get("vendorId")), "FreshMart Indiranagar"),
                new Price(new BigDecimal("100.00"), new BigDecimal("90.00"), "INR", 10), true,
                new BigDecimal("4.50"), 12, "Pasteurised toned milk.");
        given(products.lookup(List.of(productId, missingProductId)))
                .willReturn(new ProductLookupResponse(List.of(card), List.of(missingProductId.toString())));
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
