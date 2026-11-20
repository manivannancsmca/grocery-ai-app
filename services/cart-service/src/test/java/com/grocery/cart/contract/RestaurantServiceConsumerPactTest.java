package com.grocery.cart.contract;

import au.com.dius.pact.consumer.MockServer;
import au.com.dius.pact.consumer.dsl.PactDslWithProvider;
import au.com.dius.pact.consumer.junit5.PactConsumerTestExt;
import au.com.dius.pact.consumer.junit5.PactTestFor;
import au.com.dius.pact.consumer.model.MockServerImplementation;
import au.com.dius.pact.core.model.V4Pact;
import au.com.dius.pact.core.model.annotations.Pact;
import com.grocery.cart.client.DownstreamDtos.MenuQuote;
import com.grocery.cart.client.Outcome;
import com.grocery.cart.client.RestaurantClient;
import com.grocery.cart.config.CartProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.grocery.cart.contract.ConsumerPacts.BEARER;
import static com.grocery.cart.contract.ConsumerPacts.BEARER_REGEX;
import static com.grocery.cart.contract.ConsumerPacts.CALL;
import static com.grocery.cart.contract.ConsumerPacts.CONSUMER;
import static com.grocery.cart.contract.ConsumerPacts.JSON;
import static com.grocery.cart.contract.ConsumerPacts.JSON_REGEX;
import static com.grocery.cart.contract.ConsumerPacts.MENU_ITEM_ID;
import static com.grocery.cart.contract.ConsumerPacts.MISSING_MENU_ITEM_ID;
import static com.grocery.cart.contract.ConsumerPacts.PROBLEM;
import static com.grocery.cart.contract.ConsumerPacts.PROBLEM_REGEX;
import static com.grocery.cart.contract.ConsumerPacts.RESTAURANT_ID;
import static com.grocery.cart.contract.ConsumerPacts.UNKNOWN_RESTAURANT_ID;
import static com.grocery.cart.contract.ConsumerPacts.body;
import static com.grocery.cart.contract.ConsumerPacts.ids;
import static com.grocery.cart.contract.ConsumerPacts.problem;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * cart-service → restaurant-service: {@code POST /internal/restaurants/{restaurantId}/menu-quote}
 * ({@link RestaurantClient}). Writes {@code contracts/pacts/cart-service-restaurant-service.json}. The restaurant is
 * answered in any status (the cart decides what is orderable); items not on its menu are listed in {@code missing};
 * 404 RESTAURANT_NOT_FOUND means "does not exist" (→ NotFound), any other error → Failed.
 */
@ExtendWith(PactConsumerTestExt.class)
@PactTestFor(providerName = "restaurant-service", mockServerImplementation = MockServerImplementation.JavaHttpServer)
class RestaurantServiceConsumerPactTest {

    // ------------------------------------------------------------------------------------------------ pacts

    @Pact(consumer = CONSUMER)
    V4Pact menuQuote(PactDslWithProvider builder) {
        return builder
                .given("an approved restaurant with a dish on its menu",
                        Map.of("restaurantId", RESTAURANT_ID.toString(), "menuItemId", MENU_ITEM_ID.toString(),
                                "missingMenuItemId", MISSING_MENU_ITEM_ID.toString()))
                .uponReceiving("a menu quote of a dish on the menu and one that is not")
                .method("POST")
                .path(path(RESTAURANT_ID))
                .matchHeader("Authorization", BEARER_REGEX, BEARER)
                .matchHeader("Content-Type", JSON_REGEX, JSON)
                .body(ids("itemIds", MENU_ITEM_ID, MISSING_MENU_ITEM_ID))
                .willRespondWith()
                .status(200)
                .matchHeader("Content-Type", JSON_REGEX, JSON)
                .body(body(o -> {
                    o.uuid("restaurantId", RESTAURANT_ID);
                    o.stringType("name", "Spice Route");
                    o.stringValue("status", "APPROVED");
                    o.booleanType("acceptingOrders", true);
                    o.booleanType("openNow", true);
                    o.stringType("city", "Bengaluru");
                    o.numberType("latitude", 12.9352);
                    o.numberType("longitude", 77.6245);
                    o.eachLike("items", item -> item
                            .uuid("itemId", MENU_ITEM_ID)
                            .stringType("name", "Paneer Tikka")
                            .numberType("price", new BigDecimal("220.00"))
                            .stringType("foodType", "VEG")
                            .booleanType("available", true)
                            .stringType("imageUrl", "https://cdn.grocery.local/menu/paneer-tikka.jpg"));
                    o.array("missing", missing -> missing.uuid(MISSING_MENU_ITEM_ID.toString()));
                }))
                .toPact(V4Pact.class);
    }

    @Pact(consumer = CONSUMER)
    V4Pact unknownRestaurant(PactDslWithProvider builder) {
        return builder
                .given("no restaurant exists with the id", Map.of("restaurantId", UNKNOWN_RESTAURANT_ID.toString()))
                .uponReceiving("a menu quote of an unknown restaurant")
                .method("POST")
                .path(path(UNKNOWN_RESTAURANT_ID))
                .matchHeader("Authorization", BEARER_REGEX, BEARER)
                .matchHeader("Content-Type", JSON_REGEX, JSON)
                .body(ids("itemIds", MENU_ITEM_ID))
                .willRespondWith()
                .status(404)
                .matchHeader("Content-Type", PROBLEM_REGEX, PROBLEM)
                .body(problem("RESTAURANT_NOT_FOUND"))
                .toPact(V4Pact.class);
    }

    private static String path(UUID restaurantId) {
        return "/internal/restaurants/" + restaurantId + "/menu-quote";
    }

    // ------------------------------------------------------------------------------------------------ tests

    private static RestaurantClient client(MockServer server) {
        CartProperties properties = ConsumerPacts.properties(server);
        return new RestaurantClient(ConsumerPacts.downstream(properties), properties);
    }

    @Test
    @PactTestFor(pactMethod = "menuQuote")
    void readsTheRestaurantAndItsDishes(MockServer server) {
        Outcome<MenuQuote> outcome = client(server).menuQuote(CALL, RESTAURANT_ID,
                List.of(MENU_ITEM_ID, MISSING_MENU_ITEM_ID));

        assertThat(outcome).isInstanceOf(Outcome.Ok.class);
        MenuQuote quote = ((Outcome.Ok<MenuQuote>) outcome).value();
        assertThat(quote.restaurantId()).isEqualTo(RESTAURANT_ID);
        assertThat(quote.approved()).isTrue();
        assertThat(quote.isAcceptingOrders()).isTrue();
        assertThat(quote.isOpenNow()).isTrue();
        assertThat(quote.latitude()).isNotNull();
        assertThat(quote.byId()).containsOnlyKeys(MENU_ITEM_ID);
        assertThat(quote.byId().get(MENU_ITEM_ID)).satisfies(item -> {
            assertThat(item.orderable()).isTrue();
            assertThat(item.price()).isEqualByComparingTo("220.00");
            assertThat(item.foodType()).isEqualTo("VEG");
        });
        assertThat(quote.missing()).containsExactly(MISSING_MENU_ITEM_ID);
    }

    @Test
    @PactTestFor(pactMethod = "unknownRestaurant")
    void mapsRestaurantNotFoundToNotFound(MockServer server) {
        assertThat(client(server).menuQuote(CALL, UNKNOWN_RESTAURANT_ID, List.of(MENU_ITEM_ID)))
                .isInstanceOf(Outcome.NotFound.class);
    }
}
