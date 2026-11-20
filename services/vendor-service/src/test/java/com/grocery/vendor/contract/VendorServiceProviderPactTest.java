package com.grocery.vendor.contract;

import au.com.dius.pact.provider.junit5.PactVerificationContext;
import au.com.dius.pact.provider.junitsupport.Provider;
import au.com.dius.pact.provider.junitsupport.State;
import au.com.dius.pact.provider.junitsupport.loader.PactFolder;
import au.com.dius.pact.provider.spring.spring7.PactVerificationSpring7Provider;
import au.com.dius.pact.provider.spring.spring7.Spring7MockMvcTestTarget;
import com.grocery.vendor.AbstractIntegrationTest;
import com.grocery.vendor.entity.BusinessAddress;
import com.grocery.vendor.entity.Vendor;
import com.grocery.vendor.repository.VendorRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.TestTemplate;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;

/**
 * vendor-service as a provider: verifies every pact in {@code contracts/pacts} whose provider is vendor-service
 * (today cart-service's {@code GET /internal/vendors/{vendorId}/store}) against this service's Spring context through
 * MockMvc. The provider states stub {@link VendorRepository} only, so the real security chain, controller,
 * {@code VendorStoreService}, mapper, Jackson and the 404 VENDOR_NOT_FOUND problem all run.
 *
 * <p>Auth: {@code /internal/**} accepts any authenticated caller (callers forward the customer's token). The pacts
 * carry an opaque example bearer token; the {@link JwtDecoder} is a Mockito bean that turns any bearer token into a
 * CUSTOMER JWT, so no real signed token is needed. Token validation itself is covered by the security tests.
 */
@Provider("vendor-service")
@PactFolder("../../contracts/pacts")
class VendorServiceProviderPactTest extends AbstractIntegrationTest {

    private static final UUID CALLER_ID = UUID.fromString("0193f1a2-5b6c-7d8e-9f01-a2b3c4d5e601");
    private static final Instant NOW = Instant.parse("2026-11-14T09:00:00Z");

    @MockitoBean
    VendorRepository vendors;
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

    @State("an approved vendor with a store location")
    void approvedVendor(Map<String, Object> params) {
        UUID vendorId = uuid(params, "vendorId");
        Vendor vendor = Vendor.draft(vendorId, UUID.randomUUID(), "FreshMart Indiranagar", "owner@freshmart.example",
                "+919800000002", NOW);
        vendor.updateDetails("FreshMart Indiranagar", "FreshMart Retail Private Limited", "29ABCDE1234F1Z5",
                "11223344556677", "owner@freshmart.example", "+919800000002",
                new BusinessAddress("100 Feet Road", "Indiranagar", "Bengaluru", "Karnataka", "560038", "IN",
                        12.9719, 77.6412), NOW);
        vendor.submit(NOW);
        vendor.approve(NOW);
        given(vendors.findById(vendorId)).willReturn(Optional.of(vendor));
    }

    @State("no vendor exists with the id")
    void noVendor(Map<String, Object> params) {
        given(vendors.findById(uuid(params, "vendorId"))).willReturn(Optional.empty());
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
