package com.grocery.customer.contract;

import au.com.dius.pact.provider.junit5.PactVerificationContext;
import au.com.dius.pact.provider.junitsupport.Provider;
import au.com.dius.pact.provider.junitsupport.State;
import au.com.dius.pact.provider.junitsupport.loader.PactFolder;
import au.com.dius.pact.provider.spring.spring7.PactVerificationSpring7Provider;
import au.com.dius.pact.provider.spring.spring7.Spring7MockMvcTestTarget;
import com.grocery.customer.AbstractIntegrationTest;
import com.grocery.customer.entity.CustomerAddress;
import com.grocery.customer.repository.CustomerAddressRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.TestTemplate;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.data.domain.Sort;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;

/**
 * customer-service as a provider: verifies every pact in {@code contracts/pacts} whose provider is customer-service
 * (today cart-service's reads of the caller's addresses, {@code GET /api/customers/me/addresses[/{id}]}) against this
 * service's Spring context through MockMvc. The provider states stub {@link CustomerAddressRepository} only, so the
 * real security chain, {@code @PreAuthorize("hasRole('CUSTOMER')")}, controller, {@code AddressBookService}, mapper,
 * Jackson and the 404 ADDRESS_NOT_FOUND problem all run.
 *
 * <p>Auth: the pacts carry an opaque example bearer token. The {@link JwtDecoder} is a Mockito bean that turns any
 * bearer token into a CUSTOMER JWT whose subject is the provider state's {@code customerId}; the repository stubs
 * answer for that customer only, so the contract also checks that "me" is the token's subject. No real signed token
 * is needed; token validation itself is covered by the security tests.
 */
@Provider("customer-service")
@PactFolder("../../contracts/pacts")
class CustomerServiceProviderPactTest extends AbstractIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-11-14T09:00:00Z");

    @MockitoBean
    CustomerAddressRepository addresses;
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

    @State("the customer has a default address with a location")
    void defaultAddress(Map<String, Object> params) {
        customerId = uuid(params, "customerId");
        UUID addressId = uuid(params, "addressId");
        CustomerAddress address = CustomerAddress.create(addressId, customerId, NOW);
        address.updateDetails("Home", "Asha Rao", "+919800000001", "12, 4th Cross, HAL 2nd Stage", "Indiranagar",
                "Near the metro station", "Bengaluru", "Karnataka", "560038", "IN", 12.9784, 77.6408, NOW);
        address.setDefault(true, NOW);
        given(addresses.findByIdAndCustomerId(addressId, customerId)).willReturn(Optional.of(address));
        given(addresses.findByCustomerId(eq(customerId), any(Sort.class))).willReturn(List.of(address));
    }

    @State("the address is not one of the customer's")
    void unknownAddress(Map<String, Object> params) {
        customerId = uuid(params, "customerId");
        given(addresses.findByIdAndCustomerId(uuid(params, "addressId"), customerId)).willReturn(Optional.empty());
    }

    // ------------------------------------------------------------------------------------------------ helpers

    private static Jwt customerJwt(String token, UUID subject) {
        Instant now = Instant.now();
        return Jwt.withTokenValue(token)
                .header("alg", "none")
                .subject(subject.toString())
                .claim("roles", List.of("CUSTOMER"))
                .claim("email", "contract-test@example.com")
                .claim("name", "Contract Test")
                .issuedAt(now)
                .expiresAt(now.plusSeconds(300))
                .build();
    }

    private static UUID uuid(Map<String, Object> params, String name) {
        return UUID.fromString(String.valueOf(params.get(name)));
    }
}
