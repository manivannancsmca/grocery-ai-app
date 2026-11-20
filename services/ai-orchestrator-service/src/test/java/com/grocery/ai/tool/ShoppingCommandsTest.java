package com.grocery.ai.tool;

import com.grocery.ai.tool.ShoppingCommands.Add;
import com.grocery.ai.tool.ShoppingCommands.Item;
import com.grocery.ai.tool.ShoppingCommands.Pick;
import com.grocery.ai.tool.ShoppingCommands.PriceOrder;
import com.grocery.ai.tool.ShoppingCommands.PriceSearch;
import com.grocery.ai.tool.ShoppingCommands.Reply;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;

/** ShoppingCommands' parsing and choices: the words customers type, as in the live tests of 2026-11-19. */
class ShoppingCommandsTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static Item only(String message) {
        ShoppingCommands.Command command = ShoppingCommands.parse(message);
        assertThat(command).as(message).isInstanceOf(Add.class);
        assertThat(((Add) command).items()).as(message).hasSize(1);
        return ((Add) command).items().getFirst();
    }

    @Test
    void cartCheckoutAndOrderCommands() {
        assertThat(ShoppingCommands.parse("show my cart")).isInstanceOf(ShoppingCommands.ShowCart.class);
        assertThat(ShoppingCommands.parse("What's in my cart now?")).isInstanceOf(ShoppingCommands.ShowCart.class);
        assertThat(ShoppingCommands.parse("Checkout")).isInstanceOf(ShoppingCommands.Preview.class);
        assertThat(ShoppingCommands.parse("how much will it cost with delivery?"))
                .isInstanceOf(ShoppingCommands.Preview.class);
        assertThat(ShoppingCommands.parse("ok place the order")).isInstanceOf(ShoppingCommands.PlaceOrder.class);
        assertThat(ShoppingCommands.parse("Place my order please")).isInstanceOf(ShoppingCommands.PlaceOrder.class);
        assertThat(ShoppingCommands.parse("clear my cart")).isInstanceOf(ShoppingCommands.ClearCart.class);
        assertThat(ShoppingCommands.parse("remove everything from my cart"))
                .isInstanceOf(ShoppingCommands.ClearCart.class);
    }

    @Test
    void addingWithNumbersPacksWeightsAndOtherLanguages() {
        Item rice = only("pls add 2 basmati rice");
        assertThat(rice.what()).isEqualTo("basmati rice");
        assertThat(rice.count()).isEqualTo(2);
        assertThat(rice.counted()).isTrue();

        Item suffix = only("basmati rice 2 add pannu");                 // Tamil: "add it"
        assertThat(suffix.what()).isEqualTo("basmati rice");
        assertThat(suffix.count()).isEqualTo(2);

        Item atta = only("atta 5 kilo add");
        assertThat(atta.what()).isEqualTo("atta");
        assertThat(atta.amount()).isEqualByComparingTo("5000");
        assertThat(atta.volume()).isFalse();

        Item milk = only("can you add 500 ml milk to my cart?");
        assertThat(milk.what()).isEqualTo("milk");
        assertThat(milk.amount()).isEqualByComparingTo("500");
        assertThat(milk.volume()).isTrue();

        Add two = (Add) ShoppingCommands.parse("add a kg of tomatoes and 2 packets of milk");
        assertThat(two.items()).hasSize(2);
        assertThat(two.items().get(0).what()).isEqualTo("tomatoes");
        assertThat(two.items().get(0).amount()).isEqualByComparingTo("1000");
        assertThat(two.items().get(1).what()).isEqualTo("milk");
        assertThat(two.items().get(1).count()).isEqualTo(2);
        assertThat(two.items().get(1).packUnit()).isTrue();

        Item plain = only("add apples");
        assertThat(plain.what()).isEqualTo("apples");
        assertThat(plain.count()).isEqualTo(1);
        assertThat(plain.counted()).isFalse();
    }

    @Test
    void referencesToTheProductsShown() {
        Item that = only("add 2 packets of that");
        assertThat(that.what()).isNull();
        assertThat(that.reference()).isEqualTo(ShoppingCommands.THAT);
        assertThat(that.count()).isEqualTo(2);
        assertThat(only("add the second one").reference()).isEqualTo(1);
        assertThat(only("add the last one").reference()).isEqualTo(ShoppingCommands.LAST);
    }

    @Test
    void removingAndSettingQuantities() {
        ShoppingCommands.Remove remove = (ShoppingCommands.Remove) ShoppingCommands.parse("remove the tomatoes");
        assertThat(remove.item().what()).isEqualTo("tomatoes");
        assertThat(remove.item().counted()).isFalse();
        ShoppingCommands.Remove one = (ShoppingCommands.Remove) ShoppingCommands.parse("remove 1 milk from my cart");
        assertThat(one.item().what()).isEqualTo("milk");
        assertThat(one.item().counted()).isTrue();
        assertThat(ShoppingCommands.parse("set rice to 3")).isEqualTo(new ShoppingCommands.SetQuantity("rice", 3));
        assertThat(ShoppingCommands.parse("change the basmati rice quantity to two"))
                .isEqualTo(new ShoppingCommands.SetQuantity("basmati rice", 2));
    }

    @Test
    void bareRepliesToAQuestion() {
        assertThat(ShoppingCommands.parse("the second one")).isEqualTo(new Reply(2, null, false));
        assertThat(ShoppingCommands.parse("2")).isEqualTo(new Reply(null, 2, false));
        assertThat(ShoppingCommands.parse("one pack")).isEqualTo(new Reply(null, 1, false));
        assertThat(ShoppingCommands.parse("Yes")).isEqualTo(new Reply(null, null, true));
    }

    @Test
    void everythingElseIsLeftToTheModel() {
        for (String message : List.of("add tomatoes from adyar organic basket",
                "remove my address", "where is my order", "add 2", "add 2 dozen eggs", "hello",
                "I want to return the milk", "make it 3",
                "add organic apples or bananas", "show me milk from the dairy section", "do you have biryani?",
                "show me my orders", "what do you have", "which restaurants are open",
                // price questions with another condition, a comparison or no product: the model
                "the best rice under ₹100", "cheapest milk from adyar", "anything under ₹50",
                "how many products under 50?", "which is cheaper, amul or aavin milk?", "rice under 5 kg",
                "cheapest biryani", "is milk expensive?", "how much did I spend?", "add cheap snacks",
                "which one is the cheapest?", "how much is it?", "milk not more than ₹50", "how much for 2 kg rice")) {
            assertThat(ShoppingCommands.parse(message)).as(message).isNull();
        }
    }

    private static PriceSearch price(String what, String min, String max, PriceOrder order) {
        return new PriceSearch(what, min == null ? null : new BigDecimal(min), max == null ? null : new BigDecimal(max),
                order, null);
    }

    @Test
    void priceQuestionsAreCommands() {
        assertThat(ShoppingCommands.parse("What's the cheapest milk you have?"))
                .isEqualTo(price("milk", null, null, PriceOrder.CHEAPEST));
        assertThat(ShoppingCommands.parse("any ghee under ₹400?"))
                .isEqualTo(price("ghee", null, "400", PriceOrder.LIST));
        assertThat(ShoppingCommands.parse("ghee below rs. 400")).isEqualTo(price("ghee", null, "400", PriceOrder.LIST));
        assertThat(ShoppingCommands.parse("is there milk for less than 50 rupees"))
                .isEqualTo(price("milk", null, "50", PriceOrder.LIST));
        assertThat(ShoppingCommands.parse("find paneer under 200")).isEqualTo(price("paneer", null, "200",
                PriceOrder.LIST));
        assertThat(ShoppingCommands.parse("basmati rice between ₹100 and ₹200"))
                .isEqualTo(price("basmati rice", "100", "200", PriceOrder.LIST));
        assertThat(ShoppingCommands.parse("rice from 200 to 100 rupees"))
                .isEqualTo(price("rice", "100", "200", PriceOrder.LIST));
        assertThat(ShoppingCommands.parse("do you have ghee above ₹300?"))
                .isEqualTo(price("ghee", "300", null, PriceOrder.LIST));
        assertThat(ShoppingCommands.parse("which is the cheapest rice under ₹1,000?"))
                .isEqualTo(price("rice", null, "1000", PriceOrder.CHEAPEST));
        assertThat(ShoppingCommands.parse("the most expensive ghee")).isEqualTo(price("ghee", null, null,
                PriceOrder.DEAREST));
        assertThat(ShoppingCommands.parse("lowest price toned milk")).isEqualTo(price("toned milk", null, null,
                PriceOrder.CHEAPEST));
        assertThat(ShoppingCommands.parse("show me cheap snacks")).isEqualTo(price("snacks", null, null,
                PriceOrder.LIST));
        // a price: the prices, cheapest first
        assertThat(ShoppingCommands.parse("how much is paneer?")).isEqualTo(price("paneer", null, null,
                PriceOrder.LIST));
        assertThat(ShoppingCommands.parse("milk price")).isEqualTo(price("milk", null, null, PriceOrder.LIST));
        assertThat(ShoppingCommands.parse("what's the price of tomatoes")).isEqualTo(price("tomatoes", null, null,
                PriceOrder.LIST));
        assertThat(ShoppingCommands.parse("how much does basmati rice cost")).isEqualTo(price("basmati rice", null,
                null, PriceOrder.LIST));
        // the cart's total is still the checkout preview
        assertThat(ShoppingCommands.parse("how much is my order?")).isInstanceOf(ShoppingCommands.Preview.class);
    }

    @Test
    void addingTheCheapest() {
        assertThat(ShoppingCommands.parse("add the cheapest milk")).isEqualTo(new PriceSearch("milk", null, null,
                PriceOrder.CHEAPEST, new Item(1, false, false, null, false, "milk", null)));
        assertThat(ShoppingCommands.parse("add 2 packets of the cheapest milk")).isEqualTo(new PriceSearch("milk", null,
                null, PriceOrder.CHEAPEST, new Item(2, true, true, null, false, "milk", null)));
        assertThat(ShoppingCommands.parse("add the cheapest milk under 50")).isEqualTo(new PriceSearch("milk", null,
                new BigDecimal("50"), PriceOrder.CHEAPEST, new Item(1, false, false, null, false, "milk", null)));
        assertThat(ShoppingCommands.parse("add 2 milk under ₹50")).isEqualTo(new PriceSearch("milk", null,
                new BigDecimal("50"), PriceOrder.LIST, new Item(2, true, false, null, false, "milk", null)));
        assertThat(ShoppingCommands.parse("add a kg of the cheapest tomatoes")).isEqualTo(new PriceSearch("tomatoes",
                null, null, PriceOrder.CHEAPEST, new Item(1, false, false, new BigDecimal("1000"), false, "tomatoes",
                        null)));
    }

    private static JsonNode listing(String name, String price, String store, String category, boolean inStock) {
        return JSON.readTree("{\"id\":\"" + UUID.randomUUID() + "\",\"name\":\"" + name + "\",\"brand\":null,"
                + "\"category\":{\"name\":\"" + category + "\"},\"price\":{\"sellingPrice\":" + price + "},"
                + "\"vendor\":{\"id\":\"" + store + "\",\"name\":\"Store " + store + "\"},\"inStock\":" + inStock
                + "}");
    }

    private static List<String> names(List<JsonNode> cards) {
        return cards.stream().map(card -> card.get("name").asString()).toList();
    }

    @Test
    void thePricesOfTheProductsTheWordsMean() {
        // the search also matches descriptions: sorted by price, Halwa and Ladoo came before Ghee (live, 2026-11-20)
        List<JsonNode> ghee = List.of(listing("Halwa", "108", "a", "Chocolates and Sweets", true),
                listing("Ladoo", "225", "a", "Chocolates and Sweets", true),
                listing("Ghee", "340", "b", "Dairy Products", true));
        assertThat(names(ShoppingCommands.relevant(ghee, "ghee", NO_DESCRIPTION))).containsExactly("Ghee");
        // "milk" is a milk: not Milk Bread, Milk Chocolate or Curd
        List<JsonNode> milk = List.of(listing("Milk", "33", "a", "Dairy Products", true),
                listing("Curd", "42", "a", "Dairy Products", true), listing("Milk Bread", "46", "a", "Bakery Products",
                        true), listing("Aavin Full Cream Milk", "68", "b", "Dairy Products", true));
        assertThat(names(ShoppingCommands.relevant(milk, "milk", NO_DESCRIPTION)))
                .containsExactly("Milk", "Aavin Full Cream Milk");
        // a general word: a category of exactly that kind, never one of several kinds
        List<JsonNode> fruits = List.of(listing("Apple", "180", "a", "Fruits", true),
                listing("Cashew", "250", "a", "Dry Fruits and Nuts", true), listing("Paneer", "90", "a",
                        "Dairy Products", true));
        assertThat(names(ShoppingCommands.relevant(fruits, "fruits", NO_DESCRIPTION))).containsExactly("Apple");
        assertThat(names(ShoppingCommands.relevant(fruits, "dairy", NO_DESCRIPTION))).containsExactly("Paneer");
    }

    /** No product's description has the words. */
    private static final Predicate<JsonNode> NO_DESCRIPTION = card -> false;

    @Test
    void aCategoryOfSeveralKindsCountsOnlyTheProductsDescribedSo() {
        // live (2026-11-20): "cheapest biscuits" listed Dog Biscuits (Pet Care) and missed Britannia Marie Gold (₹35),
        // whose name has no "biscuit" but whose description is "Light, crisp Marie tea biscuits"
        List<JsonNode> biscuits = List.of(listing("Britannia Marie Gold", "35", "a", "Biscuits and Cookies", true),
                listing("Britannia Good Day Biscuits", "40", "a", "Biscuits and Cookies", true),
                listing("Bourbon", "45", "a", "Biscuits and Cookies", true),
                listing("Dog Biscuits", "220", "a", "Pet Care", true));
        List<String> read = new ArrayList<>();
        assertThat(names(ShoppingCommands.relevant(biscuits, "biscuits", described("biscuits", read))))
                .containsExactly("Britannia Marie Gold", "Britannia Good Day Biscuits");
        assertThat(read).as("only products named otherwise are read").containsExactly("Britannia Marie Gold",
                "Bourbon");
        // the other kind of the category, by its description
        assertThat(names(ShoppingCommands.relevant(biscuits, "cookies", described("cookies", new ArrayList<>()))))
                .containsExactly("Britannia Good Day Biscuits");
        // pet products only when a pet is asked for
        assertThat(names(ShoppingCommands.relevant(biscuits, "dog biscuits", NO_DESCRIPTION)))
                .containsExactly("Dog Biscuits");

        // the rest of a category of several kinds never counts by the category alone
        List<JsonNode> eggs = List.of(listing("Farm Fresh Eggs", "84", "a", "Eggs, Meat and Seafood", true),
                listing("Chicken Drumsticks", "220", "a", "Eggs, Meat and Seafood", true),
                listing("Suguna Farm Eggs", "90", "a", "Dairy Products", true));
        assertThat(names(ShoppingCommands.relevant(eggs, "eggs", described("eggs", new ArrayList<>()))))
                .as("chicken is not an egg; eggs in another category still are").containsExactly("Farm Fresh Eggs",
                        "Suguna Farm Eggs");
        assertThat(ShoppingCommands.relevant(List.of(listing("Halwa", "108", "a", "Chocolates and Sweets", true)),
                "chocolate", described("chocolate", new ArrayList<>()))).as("Halwa is not a chocolate").isEmpty();
    }

    /** Descriptions as in the Chennai catalogue; {@code read} records whose description was read. */
    private static Predicate<JsonNode> described(String what, List<String> read) {
        Map<String, String> descriptions = Map.of("Britannia Marie Gold", "Light, crisp Marie tea biscuits.",
                "Britannia Good Day Biscuits", "Crunchy, buttery cashew cookies for tea time.",
                "Bourbon", "Chocolate cream sandwich.",
                "Chicken Drumsticks", "Fresh chicken drumsticks, cleaned and ready for fry or curry.",
                "Halwa", "Rich, ghee-roasted halwa.");
        return card -> {
            String name = card.get("name").asString();
            read.add(name);
            return ShoppingCommands.words(descriptions.getOrDefault(name, ""))
                    .containsAll(ShoppingCommands.words(what));
        };
    }

    @Test
    void answersSayAreForWordsThatNameSeveralThings() {
        for (String several : List.of("tomatoes", "biscuits", "eggs", "oats", "dairy products", "Peas", "berries")) {
            assertThat(ShoppingCommands.plural(several)).as(several).isTrue();
        }
        for (String one : List.of("ghee", "milk", "basmati rice", "glass", "hummus", "dal", "gas", "")) {
            assertThat(ShoppingCommands.plural(one)).as(one).isFalse();
        }
    }

    @Test
    void oneListingPerProductByPrice() {
        // one listing per product, in stock: the cheapest, then the nearest store; the products by that price
        List<JsonNode> listings = List.of(listing("Aavin Full Cream Milk", "68", "far", "Dairy Products", true),
                listing("Milk", "35", "near", "Dairy Products", true), listing("Milk", "33", "far", "Dairy Products",
                        true), listing("Aavin Full Cream Milk", "68", "near", "Dairy Products", true),
                listing("Aavin Toned Milk", "30", "near", "Dairy Products", false));
        List<JsonNode> ranked = ShoppingCommands.ranked(listings, List.of("near", "far"), false);
        assertThat(names(ranked)).containsExactly("Milk", "Aavin Full Cream Milk");
        assertThat(ranked.get(0).get("vendor").get("id").asString()).isEqualTo("far");     // ₹33 beats ₹35
        assertThat(ranked.get(1).get("vendor").get("id").asString()).isEqualTo("near");    // same price: nearer
        assertThat(names(ShoppingCommands.ranked(listings, List.of("near", "far"), true)))
                .containsExactly("Aavin Full Cream Milk", "Milk");

        assertThat(ShoppingCommands.priceRange(null, new BigDecimal("400"), true)).isEqualTo(" at ₹400.00 or less");
        assertThat(ShoppingCommands.priceRange(new BigDecimal("300"), null, false)).isEqualTo("₹300.00 or more");
        assertThat(ShoppingCommands.priceRange(BigDecimal.ONE, BigDecimal.TEN, true))
                .isEqualTo(" between ₹1.00 and ₹10.00");
    }

    @Test
    void theProductsAPriceAnswerShowedAreTheOnesReferredTo() {
        // the answer's note: the tool's own result (sorted by price, with Halwa first), then the list it showed
        String ghee = UUID.randomUUID().toString();
        String note = "[From the app, not the customer] What the tools returned …:\nsearchProducts → {\"products\":"
                + "[{\"id\":\"" + UUID.randomUUID() + "\",\"name\":\"Halwa\"},{\"id\":\"" + ghee
                + "\",\"name\":\"Ghee\"}]}\nquickChoice → {\"ask\":\"shown\",\"options\":[\"Ghee\"],\"ids\":[\"" + ghee
                + "\"]}";
        String cart = "[From the app, not the customer] What the tools returned …:\ngetCart → {\"cart\":{}}";

        assertThat(ShoppingCommands.referenced(0, null, List.of(note))).isEqualTo("Ghee");
        // still after another command in between
        assertThat(ShoppingCommands.referenced(ShoppingCommands.THAT, null, List.of(note, cart))).isEqualTo("Ghee");
        assertThat(ShoppingCommands.shown(null, List.of(note, cart)).get("ids").get(0).asString()).isEqualTo(ghee);
        // the model searched since: its products
        String model = "[From the app, not the customer] What the tools returned …:\nsearchProducts → {\"products\":"
                + "[{\"id\":\"" + UUID.randomUUID() + "\",\"name\":\"Toor Dal\"}]}";
        assertThat(ShoppingCommands.referenced(ShoppingCommands.THAT, null, List.of(note, model)))
                .isEqualTo("Toor Dal");
        assertThat(ShoppingCommands.shown(null, List.of(note, model))).isNull();
    }

    @Test
    void productQuestionsAreSearches() {
        assertThat(ShoppingCommands.parse("do u have basmati rice?"))
                .isEqualTo(new ShoppingCommands.Search("basmati rice"));
        assertThat(ShoppingCommands.parse("which biscuits do you have?"))
                .isEqualTo(new ShoppingCommands.Search("biscuits"));
        assertThat(ShoppingCommands.parse("find me some paneer")).isEqualTo(new ShoppingCommands.Search("paneer"));
        assertThat(ShoppingCommands.parse("atta 5 kilo irukka?")).isEqualTo(new ShoppingCommands.Search("atta"));
        assertThat(ShoppingCommands.parse("is there any ghee")).isEqualTo(new ShoppingCommands.Search("ghee"));
    }

    @Test
    void packsFollowThePackSize() {
        Item kilo = new Item(1, false, false, new BigDecimal("1000"), false, "tomato", null);
        assertThat(ShoppingCommands.packs(kilo, "1 kg")).isEqualTo(1);
        assertThat(ShoppingCommands.packs(kilo, "500 g")).isEqualTo(2);
        assertThat(ShoppingCommands.packs(new Item(1, false, false, new BigDecimal("1500.0"), false, "x", null),
                "500 g")).isEqualTo(3);
        Item twoKilos = new Item(1, false, false, new BigDecimal("2000"), false, "atta", null);
        assertThat(ShoppingCommands.packs(twoKilos, "5 kg")).as("2 kg of a 5 kg pack: ask").isNull();
        assertThat(ShoppingCommands.packs(kilo, "1 L")).as("a weight for a liquid: ask").isNull();
        assertThat(ShoppingCommands.packs(new Item(6, true, false, null, false, "eggs", null), "6 pcs"))
                .as("6 eggs, packs of 6: one pack or six?").isNull();
        assertThat(ShoppingCommands.packs(new Item(2, true, true, null, false, "eggs", null), "6 pcs")).isEqualTo(2);
        assertThat(ShoppingCommands.packs(new Item(3, true, false, null, false, "rice", null), "1 kg")).isEqualTo(3);
    }

    private static JsonNode card(String name, String brand) {
        return JSON.readTree("{\"id\":\"" + UUID.randomUUID() + "\",\"name\":\"" + name + "\",\"brand\":"
                + (brand == null ? "null" : "{\"name\":\"" + brand + "\"}") + "}");
    }

    @Test
    void theProductTheWordsMean() {
        // exactly these words
        assertThat(ShoppingCommands.pickName(List.of(card("Aavin Full Cream Milk", "Aavin"), card("Milk", null)),
                "milk").name()).isEqualTo("Milk");
        // the only name with all the words, its other words being the brand
        assertThat(ShoppingCommands.pickName(List.of(card("Daawat Basmati Rice", "Daawat")), "basmati rice").name())
                .isEqualTo("Daawat Basmati Rice");
        assertThat(ShoppingCommands.pickName(List.of(card("Tomato", null)), "tomatoes").name()).isEqualTo("Tomato");
        // other words that are not the brand: asked about
        Pick ketchup = ShoppingCommands.pickName(List.of(card("Tomato Ketchup", "Kissan")), "tomatoes");
        assertThat(ketchup.name()).isNull();
        assertThat(ketchup.options()).containsExactly("Tomato Ketchup");
        // "milk" means a milk, not Milk Bread (live: all three were offered)
        Pick milk = ShoppingCommands.pickName(List.of(card("Milk Bread", null), card("Milk Chocolate", "Amul"),
                card("Aavin Full Cream Milk", "Aavin")), "milk");
        assertThat(milk.options()).containsExactly("Aavin Full Cream Milk");
        // several names: which one
        Pick milks = ShoppingCommands.pickName(List.of(card("Aavin Full Cream Milk", "Aavin"),
                card("Aavin Toned Milk", "Aavin"), card("Aavin Full Cream Milk", "Aavin")), "aavin milk");
        assertThat(milks.options()).containsExactly("Aavin Full Cream Milk", "Aavin Toned Milk");
        assertThat(milks.exactMissing()).isFalse();
        // none has the words: "did you mean"
        Pick paneer = ShoppingCommands.pickName(List.of(card("Paneer", null), card("Pav", null)), "malai paneer");
        assertThat(paneer.options()).containsExactly("Paneer", "Pav");
        assertThat(paneer.exactMissing()).isTrue();
    }

    @Test
    void referencesReadTheProductsShownLast() {
        String older = "[From the app, not the customer] What the tools returned …:\nsearchProducts → {\"query\":"
                + "\"dal\",\"products\":[{\"id\":\"" + UUID.randomUUID() + "\",\"name\":\"Toor Dal\"}]}";
        String newest = "[From the app, not the customer] What the tools returned …:\nsearchProducts → {\"query\":"
                + "\"rice\",\"products\":[{\"id\":\"" + UUID.randomUUID() + "\",\"name\":\"Daawat Basmati Rice\","
                + "\"unitLabel\":\"1 kg\"},{\"id\":\"" + UUID.randomUUID() + "\",\"name\":\"Daawat Basmati Rice\"},"
                + "{\"id\":\"" + UUID.randomUUID() + "\",\"name\":\"Ponni Raw Ri…";   // cut at 900 characters
        List<String> notes = List.of(older, newest);

        assertThat(ShoppingCommands.lastSearchNames(notes)).containsExactly("Daawat Basmati Rice");
        assertThat(ShoppingCommands.referenced(ShoppingCommands.THAT, null, notes)).isEqualTo("Daawat Basmati Rice");
        assertThat(ShoppingCommands.referenced(0, null, notes)).isEqualTo("Daawat Basmati Rice");
        assertThat(ShoppingCommands.referenced(1, null, notes)).isNull();
        assertThat(ShoppingCommands.referenced(ShoppingCommands.THAT, null, List.of())).isNull();

        JsonNode question = JSON.readTree("{\"ask\":\"product\",\"options\":[\"Aavin Full Cream Milk\","
                + "\"Aavin Toned Milk\"]}");
        assertThat(ShoppingCommands.referenced(1, question, notes)).isEqualTo("Aavin Toned Milk");
        assertThat(ShoppingCommands.referenced(ShoppingCommands.LAST, question, notes)).isEqualTo("Aavin Toned Milk");
        assertThat(ShoppingCommands.referenced(ShoppingCommands.THAT, question, notes)).as("two options").isNull();
        // the products a search answer listed (not every search hit)
        JsonNode shown = JSON.readTree("{\"ask\":\"shown\",\"options\":[\"Ghee\"]}");
        assertThat(ShoppingCommands.referenced(ShoppingCommands.THAT, shown, notes)).isEqualTo("Ghee");
    }

    @Test
    void wordsAreLowerCaseAndSingular() {
        assertThat(ShoppingCommands.words("Tomatoes")).containsExactly("tomato");
        assertThat(ShoppingCommands.words("2 packets of Daawat Basmati Rice")).containsExactly("2", "daawat",
                "basmati", "rice");
        assertThat(ShoppingCommands.words("Green Chillies")).containsExactly("green", "chilly");
    }
}
