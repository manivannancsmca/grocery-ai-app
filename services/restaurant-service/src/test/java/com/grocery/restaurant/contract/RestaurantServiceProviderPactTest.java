package com.grocery.restaurant.contract;

import au.com.dius.pact.provider.junit5.PactVerificationContext;
import au.com.dius.pact.provider.junitsupport.Provider;
import au.com.dius.pact.provider.junitsupport.State;
import au.com.dius.pact.provider.junitsupport.loader.PactFolder;
import au.com.dius.pact.provider.spring.spring7.PactVerificationSpring7Provider;
import au.com.dius.pact.provider.spring.spring7.Spring7MockMvcTestTarget;
import com.grocery.restaurant.AbstractIntegrationTest;
import com.grocery.restaurant.entity.BusinessAddress;
import com.grocery.restaurant.entity.FoodType;
import com.grocery.restaurant.entity.MenuItem;
import com.grocery.restaurant.entity.OpeningHours;
import com.grocery.restaurant.entity.Restaurant;
import com.grocery.restaurant.repository.MenuItemRepository;
import com.grocery.restaurant.repository.RestaurantRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.TestTemplate;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalTime;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;

/**
 * restaurant-service as a provider: verifies every pact in {@code contracts/pacts} whose provider is
 * restaurant-service (today cart-service's {@code POST /internal/restaurants/{restaurantId}/menu-quote}) against this
 * service's Spring context through MockMvc. The provider states stub {@link RestaurantRepository} and
 * {@link MenuItemRepository} only, so the real security chain, request validation, controller,
 * {@code MenuQuoteService} (open now, missing items), Jackson and the 404 RESTAURANT_NOT_FOUND problem all run.
 *
 * <p>Auth: {@code /internal/**} accepts any authenticated caller (callers forward the customer's token). The pacts
 * carry an opaque example bearer token; the {@link JwtDecoder} is a Mockito bean that turns any bearer token into a
 * CUSTOMER JWT, so no real signed token is needed. Token validation itself is covered by the security tests.
 */
@Provider("restaurant-service")
@PactFolder("../../contracts/pacts")
class RestaurantServiceProviderPactTest extends AbstractIntegrationTest {

    private static final UUID CALLER_ID = UUID.fromString("0193f1a2-5b6c-7d8e-9f01-a2b3c4d5e601");
    private static final Instant NOW = Instant.parse("2026-11-14T09:00:00Z");

    @MockitoBean
    RestaurantRepository restaurants;
    @MockitoBean
    MenuItemRepository menuItems;
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

    @State("an approved restaurant with a dish on its menu")
    void approvedRestaurant(Map<String, Object> params) {
        UUID restaurantId = uuid(params, "restaurantId");
        UUID menuItemId = uuid(params, "menuItemId");
        UUID missingMenuItemId = uuid(params, "missingMenuItemId");

        Restaurant restaurant = Restaurant.draft(restaurantId, UUID.randomUUID(), "Spice Route",
                "owner@spiceroute.example", "+919800000003", NOW);
        // open around the clock (00:00–00:00 = 24 h), so "open now" does not depend on the test clock
        List<OpeningHours> week = Arrays.stream(DayOfWeek.values())
                .map(day -> new OpeningHours(day, LocalTime.MIDNIGHT, LocalTime.MIDNIGHT))
                .toList();
        restaurant.updateDetails("Spice Route", "North Indian kitchen", List.of("North Indian"), "11223344556677",
                null, "owner@spiceroute.example", "+919800000003",
                new BusinessAddress("80 Feet Road", "Koramangala 4th Block", "Bengaluru", "Karnataka", "560034", "IN",
                        12.9352, 77.6245), week, NOW);
        restaurant.submit(NOW);
        restaurant.approve(NOW);
        given(restaurants.findById(restaurantId)).willReturn(Optional.of(restaurant));

        MenuItem dish = MenuItem.create(menuItemId, restaurantId, UUID.randomUUID(), "Paneer Tikka",
                "Char-grilled cottage cheese", new BigDecimal("220.00"), FoodType.VEG,
                "https://cdn.grocery.local/menu/paneer-tikka.jpg", true, 1, NOW);
        // only the dish is on this restaurant's menu; the other requested id is absent (→ missing)
        given(menuItems.findByRestaurantIdAndIdIn(eq(restaurantId), argThat((Collection<UUID> ids) ->
                ids != null && ids.containsAll(List.of(menuItemId, missingMenuItemId)))))
                .willReturn(List.of(dish));
    }

    @State("no restaurant exists with the id")
    void noRestaurant(Map<String, Object> params) {
        given(restaurants.findById(uuid(params, "restaurantId"))).willReturn(Optional.empty());
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
