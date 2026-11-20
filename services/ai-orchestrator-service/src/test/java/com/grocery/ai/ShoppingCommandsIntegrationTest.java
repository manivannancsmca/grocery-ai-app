package com.grocery.ai;

import com.grocery.ai.SseClient.Result;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The shopping assistant's simple commands handled in code before the model (ShoppingCommands): real HTTP chat, the
 * fake gateway, the tools through ToolGuard (events, audit, memory). The model is not called for them.
 */
class ShoppingCommandsIntegrationTest extends AbstractIntegrationTest {

    @DynamicPropertySource
    static void commandsOn(DynamicPropertyRegistry registry) {
        registry.add("grocery.ai.shopping-commands", () -> "true");
    }

    private static final UUID NEAR = UUID.fromString("0192f000-0000-7000-8000-0000000000e1");
    private static final UUID FAR = UUID.fromString("0192f000-0000-7000-8000-0000000000e2");

    /** Home in Velachery, Chennai; two stores deliver there, the nearest first. */
    private void stubHomeAndStores() {
        GATEWAY.json("GET", "/api/customers/me/addresses", 200, "[{\"id\":\"" + UUID.randomUUID() + "\",\"label\":"
                + "\"Home\",\"city\":\"Chennai\",\"isDefault\":true,\"latitude\":12.9782,\"longitude\":80.212}]");
        GATEWAY.json("GET", "/api/stores/nearby", 200, "{\"deliveryLimitKm\":15,\"content\":[{\"id\":\"" + NEAR
                + "\",\"name\":\"Vijaya Nagar Fresh Mart\",\"distanceKm\":1.3,\"deliversHere\":true},{\"id\":\"" + FAR
                + "\",\"name\":\"Guindy Daily Fresh\",\"distanceKm\":4.5,\"deliversHere\":true}]}");
    }

    private static Map<String, Object> card(UUID id, String name, String unit, String price, UUID store,
                                            String storeName, String brand) {
        return Fixtures.map("id", id.toString(), "name", name, "unitLabel", unit, "foodType", "VEG",
                "brand", brand == null ? null : Fixtures.map("id", UUID.randomUUID().toString(), "name", brand),
                "vendor", Fixtures.map("id", store.toString(), "name", storeName),
                "price", Fixtures.map("mrp", new BigDecimal(price), "sellingPrice", new BigDecimal(price),
                        "currency", "INR"),
                "inStock", true);
    }

    /** The cart cart-service answers after a change: one line. */
    private static String cartWith(UUID itemId, String name, int quantity, String price) {
        return Fixtures.json(Fixtures.cart(List.of(Fixtures.cartLine(itemId, name, quantity, price))));
    }

    @Test
    void addTwoBasmatiRiceAddsTheNearestStoresListingWithoutTheModel() {
        String token = TOKENS.token(UUID.randomUUID(), "CUSTOMER");
        stubHomeAndStores();
        stubCart(Fixtures.cart(List.of()));
        UUID far = UUID.randomUUID();
        UUID near = UUID.randomUUID();
        GATEWAY.json("GET", "/api/search/products", 200, Fixtures.searchResults(List.of(
                card(far, "Daawat Basmati Rice", "1 kg", "120.00", FAR, "Guindy Daily Fresh", "Daawat"),
                card(near, "Daawat Basmati Rice", "1 kg", "120.00", NEAR, "Vijaya Nagar Fresh Mart", "Daawat"))));
        GATEWAY.on("POST", "/api/cart/items", request -> FakeGateway.Response.json(200,
                cartWith(near, "Daawat Basmati Rice", 2, "120.00")));

        Result result = chat(token, "shopping", "pls add 2 basmati rice");

        assertThat(model.prompts()).as("no model call").isEmpty();
        assertThat(toolEvents(result)).containsExactly("addToCart:STARTED", "addToCart:SUCCEEDED");
        JsonNode added = GATEWAY.requests("POST", "/api/cart/items").getFirst().json();
        assertThat(added.get("productId").asString()).isEqualTo(near.toString());
        assertThat(added.get("quantity").asInt()).isEqualTo(2);
        assertThat(result.answer()).isEqualTo("Added 2 × Daawat Basmati Rice (1 kg, ₹120.00 each) from Vijaya Nagar "
                + "Fresh Mart.\nYour cart: 2 items from Fresh Mart, subtotal ₹240.00.");
        assertThat(result.last().name()).isEqualTo("done");
        // only the stores that deliver home were searched
        FakeGateway.Request search = GATEWAY.requests("GET", "/api/search/products").getFirst();
        assertThat(search.param("q")).isEqualTo("basmati rice");
        assertThat(search.query()).contains("vendorId=" + NEAR + "&vendorId=" + FAR);
    }

    @Test
    void aProductQuestionIsAnsweredFromTheSearchAndThatIsAddedNext() {
        // Live: the model answered "which biscuits do you have?" with products that do not exist.
        String token = TOKENS.token(UUID.randomUUID(), "CUSTOMER");
        stubHomeAndStores();
        stubCart(Fixtures.cart(List.of()));
        UUID near = UUID.randomUUID();
        GATEWAY.json("GET", "/api/search/products", 200, Fixtures.searchResults(List.of(
                card(near, "Daawat Basmati Rice", "1 kg", "120.00", NEAR, "Vijaya Nagar Fresh Mart", "Daawat"),
                card(UUID.randomUUID(), "Daawat Basmati Rice", "1 kg", "120.00", FAR, "Guindy Daily Fresh",
                        "Daawat")), 6));
        GATEWAY.on("POST", "/api/cart/items", request -> FakeGateway.Response.json(200,
                cartWith(near, "Daawat Basmati Rice", 2, "120.00")));

        Result found = chat(token, "shopping", "do u have basmati rice?");

        assertThat(toolEvents(found)).containsExactly("searchProducts:STARTED", "searchProducts:SUCCEEDED");
        assertThat(found.answer()).isEqualTo("Yes, here's what I found for “basmati rice”:\n"
                + "1. Daawat Basmati Rice (1 kg) – ₹120.00\nSay “add 2 of the first one” to add one, or open the "
                + "search page to see all 6 results.");

        Result added = chat(token, "shopping", "add 2 packets of that",
                UUID.fromString(found.first("conversation").text("conversationId")));

        JsonNode body = GATEWAY.requests("POST", "/api/cart/items").getFirst().json();
        assertThat(body.get("productId").asString()).as("the nearest store's listing").isEqualTo(near.toString());
        assertThat(body.get("quantity").asInt()).isEqualTo(2);
        assertThat(added.answer()).startsWith("Added 2 × Daawat Basmati Rice");
        assertThat(model.prompts()).isEmpty();

        // the search also matches descriptions: only the products named so are listed (live: Halwa for "ghee")
        GATEWAY.json("GET", "/api/search/products", 200, Fixtures.searchResults(List.of(
                card(UUID.randomUUID(), "Ghee", "500 ml", "340.00", NEAR, "Vijaya Nagar Fresh Mart", null),
                card(UUID.randomUUID(), "Halwa", "250 g", "108.00", NEAR, "Vijaya Nagar Fresh Mart", null))));
        Result ghee = chat(token, "shopping", "do you have ghee?");
        assertThat(ghee.answer()).isEqualTo("Yes, here's what I found for “ghee”:\n1. Ghee (500 ml) – ₹340.00\n"
                + "Say “add 2 of the first one” to add one.");

        // nothing found: the model takes it (a restaurant dish, other words)
        GATEWAY.json("GET", "/api/search/products", 200, Fixtures.searchResults(List.of()));
        chat(token, "shopping", "do you have quinoa");
        assertThat(model.prompts()).isNotEmpty();
    }

    @Test
    void priceQuestionsAreAnsweredFromTheSearchAndTheListingWhosePriceWasGivenIsAdded() {
        // Live: "any ghee under ₹400?" got "There is no ghee under ₹400. The cheapest ghee available is ₹340.00"; the
        // search sorted by price put Halwa and Ladoo ("made with ghee") before the ghee.
        String token = TOKENS.token(UUID.randomUUID(), "CUSTOMER");
        stubHomeAndStores();
        stubCart(Fixtures.cart(List.of()));
        UUID cheaper = UUID.randomUUID();
        Map<String, Object> cheaperGhee = card(cheaper, "Ghee", "500 ml", "340.00", FAR, "Guindy Daily Fresh", null);
        GATEWAY.json("GET", "/api/search/products", 200, Fixtures.searchResults(List.of(
                card(UUID.randomUUID(), "Halwa", "250 g", "108.00", NEAR, "Vijaya Nagar Fresh Mart", null),
                card(UUID.randomUUID(), "Ladoo", "500 g", "225.00", NEAR, "Vijaya Nagar Fresh Mart", null),
                cheaperGhee,
                card(UUID.randomUUID(), "Ghee", "500 ml", "360.00", NEAR, "Vijaya Nagar Fresh Mart", null),
                card(UUID.randomUUID(), "Ghee", "1 L", "690.00", NEAR, "Vijaya Nagar Fresh Mart", null))));
        GATEWAY.json("GET", "/api/catalog/products/" + cheaper, 200, Fixtures.json(cheaperGhee));
        GATEWAY.on("POST", "/api/cart/items", request -> FakeGateway.Response.json(200,
                cartWith(cheaper, "Ghee", 1, "340.00")));

        Result found = chat(token, "shopping", "any ghee under ₹400?");

        assertThat(model.prompts()).as("no model call").isEmpty();
        assertThat(toolEvents(found)).containsExactly("searchProducts:STARTED", "searchProducts:SUCCEEDED");
        assertThat(found.answer()).isEqualTo("Yes, here's “ghee” at ₹400.00 or less, cheapest first:\n"
                + "1. Ghee (500 ml) – ₹340.00 at Guindy Daily Fresh\nSay “add 2 of that” to add it.");
        FakeGateway.Request search = GATEWAY.requests("GET", "/api/search/products").getFirst();
        assertThat(search.param("q")).isEqualTo("ghee");
        assertThat(search.param("maxPrice")).isEqualTo("400");
        assertThat(search.param("sort")).isEqualTo("PRICE_ASC");
        assertThat(search.query()).contains("vendorId=" + NEAR + "&vendorId=" + FAR);

        // "that" is the listing whose price was given (₹340.00 at Guindy), not the nearest store's (₹360.00)
        UUID conversation = UUID.fromString(found.first("conversation").text("conversationId"));
        Result added = chat(token, "shopping", "add 1 of that", conversation);
        JsonNode body = GATEWAY.requests("POST", "/api/cart/items").getFirst().json();
        assertThat(body.get("productId").asString()).isEqualTo(cheaper.toString());
        assertThat(added.answer()).startsWith("Added 1 × Ghee (500 ml, ₹340.00 each) from Guindy Daily Fresh.");

        // nothing between the bounds: the nearest price instead of "no ghee"
        Result above = chat(token, "shopping", "ghee below rs 300", conversation);
        assertThat(above.answer()).isEqualTo("No “ghee” costs ₹300.00 or less in the stores that deliver to Home. "
                + "The cheapest is Ghee (500 ml) at ₹340.00, from Guindy Daily Fresh.\nSay “add 2 of that” to add it.");

        Result dearest = chat(token, "shopping", "what's the most expensive ghee?", conversation);
        assertThat(dearest.answer()).isEqualTo("The most expensive “ghee” is Ghee (1 L) at ₹690.00, from Vijaya Nagar "
                + "Fresh Mart.\nMost expensive first:\n1. Ghee (1 L) – ₹690.00 at Vijaya Nagar Fresh Mart\n"
                + "2. Ghee (500 ml) – ₹340.00 at Guindy Daily Fresh\nSay “add 2 of the first one” to add one.");

        Result cheapest = chat(token, "shopping", "add the cheapest ghee", conversation);
        assertThat(cheapest.answer()).startsWith("The cheapest “ghee” is Ghee (500 ml, ₹340.00).\n"
                + "Added 1 × Ghee (500 ml, ₹340.00 each) from Guindy Daily Fresh.");
        assertThat(GATEWAY.requests("POST", "/api/cart/items").getLast().json().get("productId").asString())
                .isEqualTo(cheaper.toString());
        assertThat(model.prompts()).isEmpty();

        // no product with these words at all: the model takes it
        chat(token, "shopping", "what's the cheapest quinoa?", conversation);
        assertThat(model.prompts()).isNotEmpty();
    }

    private static Map<String, Object> inCategory(Map<String, Object> card, String category) {
        Map<String, Object> copy = new java.util.LinkedHashMap<>(card);
        copy.put("category", Fixtures.map("id", UUID.randomUUID().toString(), "name", category));
        return copy;
    }

    @Test
    void biscuitsAreTheBiscuitsCategorysBiscuitsNotDogBiscuits() {
        // Live: "cheapest biscuits" listed Dog Biscuits (Pet Care) and missed Britannia Marie Gold (₹35), whose name
        // has no "biscuit"; its description does ("Light, crisp Marie tea biscuits").
        String token = TOKENS.token(UUID.randomUUID(), "CUSTOMER");
        stubHomeAndStores();
        stubCart(Fixtures.cart(List.of()));
        UUID marieGold = UUID.randomUUID();
        UUID bourbon = UUID.randomUUID();
        GATEWAY.json("GET", "/api/search/products", 200, Fixtures.searchResults(List.of(
                inCategory(card(marieGold, "Britannia Marie Gold", "200 g", "35.00", NEAR, "Vijaya Nagar Fresh Mart",
                        "Britannia"), "Biscuits and Cookies"),
                inCategory(card(UUID.randomUUID(), "Britannia Good Day Biscuits", "200 g", "40.00", NEAR,
                        "Vijaya Nagar Fresh Mart", "Britannia"), "Biscuits and Cookies"),
                inCategory(card(bourbon, "Bourbon", "150 g", "30.00", NEAR, "Vijaya Nagar Fresh Mart", null),
                        "Biscuits and Cookies"),
                inCategory(card(UUID.randomUUID(), "Dog Biscuits", "500 g", "220.00", NEAR, "Vijaya Nagar Fresh Mart",
                        null), "Pet Care"))));
        GATEWAY.json("GET", "/api/catalog/products/" + marieGold, 200, "{\"id\":\"" + marieGold + "\",\"name\":"
                + "\"Britannia Marie Gold\",\"description\":\"Light, crisp Marie tea biscuits.\"}");
        GATEWAY.json("GET", "/api/catalog/products/" + bourbon, 200, "{\"id\":\"" + bourbon + "\",\"name\":"
                + "\"Bourbon\",\"description\":\"Chocolate cream sandwich.\"}");

        Result cheapest = chat(token, "shopping", "cheapest biscuits");

        assertThat(cheapest.answer()).isEqualTo("The cheapest “biscuits” are Britannia Marie Gold (200 g) at ₹35.00, "
                + "from Vijaya Nagar Fresh Mart.\nCheapest first:\n"
                + "1. Britannia Marie Gold (200 g) – ₹35.00 at Vijaya Nagar Fresh Mart\n"
                + "2. Britannia Good Day Biscuits (200 g) – ₹40.00 at Vijaya Nagar Fresh Mart\n"
                + "Say “add 2 of the first one” to add one.");

        Result which = chat(token, "shopping", "which biscuits do you have?");
        assertThat(which.answer()).isEqualTo("Yes, here's what I found for “biscuits”:\n"
                + "1. Britannia Marie Gold (200 g) – ₹35.00\n2. Britannia Good Day Biscuits (200 g) – ₹40.00\n"
                + "Say “add 2 of the first one” to add one.");
        assertThat(model.prompts()).isEmpty();
    }

    @Test
    void theCardsShortDescriptionsSayWhatAProductIsWithoutReadingEachProduct() {
        String token = TOKENS.token(UUID.randomUUID(), "CUSTOMER");
        stubHomeAndStores();
        UUID longOne = UUID.randomUUID();
        Map<String, Object> marieGold = inCategory(card(UUID.randomUUID(), "Britannia Marie Gold", "200 g", "35.00",
                NEAR, "Vijaya Nagar Fresh Mart", "Britannia"), "Biscuits and Cookies");
        marieGold.put("shortDescription", "Light, crisp Marie tea biscuits.");
        Map<String, Object> bourbon = inCategory(card(UUID.randomUUID(), "Bourbon", "150 g", "30.00", NEAR,
                "Vijaya Nagar Fresh Mart", null), "Biscuits and Cookies");
        bourbon.put("shortDescription", "Chocolate cream sandwich.");
        Map<String, Object> rusk = inCategory(card(UUID.randomUUID(), "Milk Rusk", "300 g", "45.00", NEAR,
                "Vijaya Nagar Fresh Mart", null), "Biscuits and Cookies");
        rusk.put("shortDescription", null);
        Map<String, Object> cut = inCategory(card(longOne, "Nankhatai", "250 g", "60.00", NEAR,
                "Vijaya Nagar Fresh Mart", null), "Biscuits and Cookies");
        cut.put("shortDescription", "A crumbly Indian shortbread baked with ghee, cardamom and…");
        GATEWAY.json("GET", "/api/search/products", 200, Fixtures.searchResults(List.of(marieGold, bourbon, rusk,
                cut)));
        GATEWAY.json("GET", "/api/catalog/products/" + longOne, 200, "{\"id\":\"" + longOne + "\",\"name\":"
                + "\"Nankhatai\",\"description\":\"A crumbly Indian shortbread baked with ghee, cardamom and nutmeg: "
                + "the bakery's biscuits.\"}");

        Result cheapest = chat(token, "shopping", "cheapest biscuits");

        assertThat(cheapest.answer()).startsWith("The cheapest “biscuits” are Britannia Marie Gold (200 g) at ₹35.00")
                .contains("2. Nankhatai (250 g) – ₹60.00").doesNotContain("Bourbon").doesNotContain("Milk Rusk");
        assertThat(GATEWAY.requests("GET", "/api/catalog/products/.*")).extracting(FakeGateway.Request::path)
                .containsExactly("/api/catalog/products/" + longOne);     // only the description cut short
        assertThat(model.prompts()).isEmpty();
    }

    @Test
    void anUnclearProductIsAskedAboutAndTheReplyPicksIt() {
        String token = TOKENS.token(UUID.randomUUID(), "CUSTOMER");
        stubHomeAndStores();
        stubCart(Fixtures.cart(List.of()));
        UUID full = UUID.randomUUID();
        UUID toned = UUID.randomUUID();
        GATEWAY.json("GET", "/api/search/products", 200, Fixtures.searchResults(List.of(
                card(full, "Aavin Full Cream Milk", "1 L", "68.00", NEAR, "Vijaya Nagar Fresh Mart", "Aavin"),
                card(toned, "Aavin Toned Milk", "500 ml", "28.00", NEAR, "Vijaya Nagar Fresh Mart", "Aavin"))));
        GATEWAY.on("POST", "/api/cart/items", request -> FakeGateway.Response.json(200,
                cartWith(toned, "Aavin Toned Milk", 2, "28.00")));

        Result asked = chat(token, "shopping", "add 2 aavin milk");
        assertThat(asked.answer()).isEqualTo("Which one would you like?\n1. Aavin Full Cream Milk (1 L, ₹68.00)\n"
                + "2. Aavin Toned Milk (500 ml, ₹28.00)\nSay “the first one”, “the second one”…");
        assertThat(GATEWAY.requests("POST", ".*")).isEmpty();

        UUID conversation = UUID.fromString(asked.first("conversation").text("conversationId"));
        Result added = chat(token, "shopping", "the second one", conversation);

        assertThat(model.prompts()).isEmpty();
        JsonNode body = GATEWAY.requests("POST", "/api/cart/items").getFirst().json();
        assertThat(body.get("productId").asString()).isEqualTo(toned.toString());
        assertThat(body.get("quantity").asInt()).as("the 2 of the question").isEqualTo(2);
        assertThat(added.answer()).startsWith("Added 2 × Aavin Toned Milk (500 ml, ₹28.00 each) from Vijaya Nagar "
                + "Fresh Mart.");
    }

    @Test
    void aWeightThatIsNotAWholeNumberOfPacksAsksHowManyPacks() {
        String token = TOKENS.token(UUID.randomUUID(), "CUSTOMER");
        stubHomeAndStores();
        stubCart(Fixtures.cart(List.of()));
        UUID atta = UUID.randomUUID();
        GATEWAY.json("GET", "/api/search/products", 200, Fixtures.searchResults(List.of(
                card(atta, "Wheat Atta", "5 kg", "260.00", NEAR, "Vijaya Nagar Fresh Mart", "Aashirvaad"))));
        GATEWAY.on("POST", "/api/cart/items", request -> FakeGateway.Response.json(200,
                cartWith(atta, "Wheat Atta", 1, "260.00")));

        Result asked = chat(token, "shopping", "add 2 kg wheat atta");
        assertThat(asked.answer()).isEqualTo("Wheat Atta comes in 5 kg packs (₹260.00 each). How many packs would you "
                + "like?");
        UUID conversation = UUID.fromString(asked.first("conversation").text("conversationId"));

        Result added = chat(token, "shopping", "1", conversation);

        JsonNode body = GATEWAY.requests("POST", "/api/cart/items").getFirst().json();
        assertThat(body.get("productId").asString()).isEqualTo(atta.toString());
        assertThat(body.get("quantity").asInt()).isEqualTo(1);
        assertThat(added.answer()).startsWith("Added 1 × Wheat Atta (5 kg, ₹260.00 each)");
    }

    @Test
    void aSimilarProductIsConfirmedFirst() {
        String token = TOKENS.token(UUID.randomUUID(), "CUSTOMER");
        stubHomeAndStores();
        stubCart(Fixtures.cart(List.of()));
        UUID ketchup = UUID.randomUUID();
        GATEWAY.json("GET", "/api/search/products", 200, Fixtures.searchResults(List.of(
                card(ketchup, "Tomato Ketchup", "200 g", "45.00", NEAR, "Vijaya Nagar Fresh Mart", "Kissan"))));
        GATEWAY.on("POST", "/api/cart/items", request -> FakeGateway.Response.json(200,
                cartWith(ketchup, "Tomato Ketchup", 1, "45.00")));

        Result asked = chat(token, "shopping", "add tomatoes");
        assertThat(asked.answer()).isEqualTo("Do you mean Tomato Ketchup (200 g, ₹45.00)? Say “yes” to add it.");
        assertThat(GATEWAY.requests("POST", ".*")).isEmpty();

        Result added = chat(token, "shopping", "yes",
                UUID.fromString(asked.first("conversation").text("conversationId")));
        assertThat(GATEWAY.requests("POST", "/api/cart/items").getFirst().json().get("quantity").asInt())
                .isEqualTo(1);
        assertThat(added.answer()).startsWith("Added 1 × Tomato Ketchup");
    }

    @Test
    void aProductFromAnotherStoreIsExplainedNotAdded() {
        // A cart holds one store: Fresh Mart's cart, tomatoes only at Vijaya Nagar Fresh Mart.
        String token = TOKENS.token(UUID.randomUUID(), "CUSTOMER");
        stubHomeAndStores();
        stubCart(Fixtures.defaultCart());
        GATEWAY.on("GET", "/api/search/products", request -> FakeGateway.Response.json(200,
                request.query().contains("vendorId=" + Fixtures.STORE) ? Fixtures.searchResults(List.of())
                        : Fixtures.searchResults(List.of(card(UUID.randomUUID(), "Tomato", "1 kg", "40.00", NEAR,
                        "Vijaya Nagar Fresh Mart", null)))));

        Result result = chat(token, "shopping", "add a kg of tomatoes");

        assertThat(result.answer()).isEqualTo("Your cart has products from Fresh Mart, which doesn't have tomatoes "
                + "(Vijaya Nagar Fresh Mart has Tomato). A cart holds one store: to order from another store, empty "
                + "your cart first (say “clear my cart”).");
        assertThat(GATEWAY.requests("POST", ".*")).isEmpty();
        assertThat(model.prompts()).isEmpty();
    }

    @Test
    void aProductInTheCartGetsMoreAndACloseMatchInTheCartsStoreIsAskedAbout() {
        // Live: "rice 1 add pannu" and "add milk" were sent to other stores although the cart's store had them.
        String token = TOKENS.token(UUID.randomUUID(), "CUSTOMER");
        stubHomeAndStores();
        stubCart(Fixtures.defaultCart());                         // Smart Rice × 2, Toor Dal × 1 at Fresh Mart
        GATEWAY.on("POST", "/api/cart/items", request -> FakeGateway.Response.json(200,
                cartWith(Fixtures.RICE, "Smart Rice", 3, "100.00")));
        UUID full = UUID.randomUUID();
        GATEWAY.on("GET", "/api/search/products", request -> FakeGateway.Response.json(200,
                request.query().contains("vendorId=" + Fixtures.STORE)
                        ? Fixtures.searchResults(List.of(card(full, "Aavin Full Cream Milk", "1 L", "68.00",
                        Fixtures.STORE, "Fresh Mart", "Aavin")))
                        : Fixtures.searchResults(List.of(card(UUID.randomUUID(), "Milk", "500 ml", "33.00", NEAR,
                        "Vijaya Nagar Fresh Mart", null)))));

        Result more = chat(token, "shopping", "rice 1 add pannu");

        assertThat(GATEWAY.requests("GET", "/api/search/products")).as("found in the cart").isEmpty();
        JsonNode body = GATEWAY.requests("POST", "/api/cart/items").getFirst().json();
        assertThat(body.get("productId").asString()).isEqualTo(Fixtures.RICE.toString());
        assertThat(body.get("quantity").asInt()).isEqualTo(1);
        assertThat(more.answer()).isEqualTo("Added 1 × Smart Rice (1 kg, ₹100.00 each).\nYour cart: 3 items from Fresh "
                + "Mart, subtotal ₹300.00.");

        Result milk = chat(token, "shopping", "add milk");
        assertThat(milk.answer()).isEqualTo("Do you mean Aavin Full Cream Milk (1 L, ₹68.00)? Say “yes” to add it.");
        assertThat(model.prompts()).isEmpty();
    }

    @Test
    void showRemoveAndClearTheCart() {
        String token = TOKENS.token(UUID.randomUUID(), "CUSTOMER");
        stubCart(Fixtures.cart(List.of(Fixtures.cartLine(Fixtures.TOMATO, "Tomato", 1, "40.00"),
                Fixtures.cartLine(Fixtures.RICE, "Smart Rice", 2, "100.00"))));
        GATEWAY.on("PUT", "/api/cart/items/" + Fixtures.TOMATO, request -> FakeGateway.Response.json(200,
                cartWith(Fixtures.RICE, "Smart Rice", 2, "100.00")));
        GATEWAY.on("PUT", "/api/cart/items/" + Fixtures.RICE, request -> FakeGateway.Response.json(200,
                Fixtures.json(Fixtures.cart(List.of()))));

        Result shown = chat(token, "shopping", "what's in my cart?");
        assertThat(toolEvents(shown)).containsExactly("getCart:STARTED", "getCart:SUCCEEDED");
        assertThat(shown.answer()).isEqualTo("Your cart from Fresh Mart:\n- 1 × Tomato (1 kg) – ₹40.00\n"
                + "- 2 × Smart Rice (1 kg) – ₹200.00\nSubtotal: ₹240.00 (3 items).");

        Result removed = chat(token, "shopping", "remove the tomatoes");
        assertThat(toolEvents(removed)).containsExactly("updateCartItem:STARTED", "updateCartItem:SUCCEEDED");
        assertThat(GATEWAY.requests("PUT", "/api/cart/items/" + Fixtures.TOMATO).getFirst().json().get("quantity")
                .asInt()).isZero();
        assertThat(removed.answer()).isEqualTo("Removed Tomato from your cart. Your cart: 2 items from Fresh Mart, "
                + "subtotal ₹200.00.");

        Result missing = chat(token, "shopping", "remove the paneer");
        assertThat(missing.answer()).isEqualTo("There's no paneer in your cart. It has Tomato, Smart Rice.");
        assertThat(toolEvents(missing)).isEmpty();

        Result cleared = chat(token, "shopping", "clear my cart");
        assertThat(cleared.answer()).isEqualTo("Emptied your cart (removed 2 products).");
        assertThat(GATEWAY.requests("PUT", "/api/cart/items/.*")).hasSize(3);
        assertThat(model.prompts()).isEmpty();
    }

    @Test
    void checkoutAndPlaceMyOrderProposeTheOrderForTheCustomerToConfirm() {
        String token = TOKENS.token(UUID.randomUUID(), "CUSTOMER");
        stubDefaultCartAndPreview();

        Result total = chat(token, "shopping", "how much will it cost with delivery?");
        assertThat(toolEvents(total)).containsExactly("previewCheckout:STARTED", "previewCheckout:SUCCEEDED");
        assertThat(total.answer()).isEqualTo("Your total is ₹285.00: ₹260.00 for 3 items from Fresh Mart + ₹25.00 "
                + "delivery to Home. Say “place my order” when you're ready.");

        Result placed = chat(token, "shopping", "ok place my order");
        assertThat(toolEvents(placed)).containsExactly("previewCheckout:STARTED", "previewCheckout:SUCCEEDED",
                "placeOrder:STARTED", "placeOrder:APPROVAL_REQUIRED");
        assertThat(placed.named("approval")).hasSize(1);
        assertThat(placed.first("approval").data().get("action").asString()).isEqualTo("PLACE_ORDER");
        assertThat(placed.answer()).isEqualTo("I've prepared your order. Your total is ₹285.00: ₹260.00 for 3 items "
                + "from Fresh Mart + ₹25.00 delivery to Home. Nothing is ordered until you tap Confirm order on the "
                + "card below.");
        assertThat(GATEWAY.requests("POST", ".*")).as("nothing ordered").isEmpty();
        assertThat(model.prompts()).isEmpty();
    }

    @Test
    void anythingElseStillGoesToTheModel() {
        String token = TOKENS.token(UUID.randomUUID(), "CUSTOMER");
        stubCart(Fixtures.defaultCart());

        for (String message : List.of("the best rice under ₹100", "hello there",
                "2" /* a bare number with no question before it */)) {
            int before = model.prompts().size();
            chat(token, "shopping", message);
            assertThat(model.prompts().size()).as(message).isGreaterThan(before);
        }
    }
}
