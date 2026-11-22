package com.grocery.ai.tool;

import com.grocery.ai.gateway.BusinessApi;
import com.grocery.ai.gateway.BusinessApi.Fetch;
import com.grocery.ai.support.Json;
import com.grocery.ai.support.Money;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * The shopping assistant's simple commands, handled in code before the model (2026-11-20). The local 4B model often
 * skips the cart tools late in a conversation ("Added 1 kg of Tomato" with the cart untouched), so the commands
 * customers type most are recognised here and run through {@link ToolGuard} exactly like the model's own tool calls
 * (permissions, audit, {@code tool} / {@code approval} events, the conversation's memory):
 * <ul>
 *   <li>"show my cart", "what's in my cart";</li>
 *   <li>"add 2 basmati rice", "add a kg of tomatoes and 2 milk", "rice 2 add pannu", "add 2 of that", "add the second
 *       one" (from the products shown last);</li>
 *   <li>"remove the tomatoes", "remove 1 milk", "set rice to 3", "clear my cart";</li>
 *   <li>"checkout", "how much will it cost with delivery";</li>
 *   <li>"place my order": an approval the customer confirms, as when the model proposes it;</li>
 *   <li>"do you have basmati rice", "which biscuits do you have";</li>
 *   <li>prices: "the cheapest milk", "any ghee under ₹400", "rice between ₹100 and ₹200", "the most expensive ghee",
 *       "how much is ghee", "add the cheapest milk" (2026-11-20: the model said "There is no ghee under ₹400. The
 *       cheapest ghee available is ₹340.00").</li>
 * </ul>
 * Anything else, and other conditions ("the best rice", "tomatoes from Adyar", a category), go to the model.
 * When the product or the number of packs is unclear the answer asks, and a bare reply to that question ("the second
 * one", "2") is understood from the question, which is remembered with the answer like a tool result.
 */
@Component
public class ShoppingCommands {

    /** The remembered question of an answer, read back from the next message ("quickChoice → {…}"). */
    static final String CHOICE = "quickChoice";
    static final int MAX_QUANTITY = 20;
    static final int MAX_ITEMS = 3;
    static final int MAX_OPTIONS = 5;
    /**
     * Different products listed for "show me all …" (2026-11-21): this answer is written by code, so a long list costs
     * nothing (the local model writes ~4–5 tokens/s: 20 lines would take it two minutes).
     */
    static final int MAX_ALL_OPTIONS = 20;
    /** "the last one" in {@link Item#reference()}. */
    static final int LAST = Integer.MAX_VALUE;
    /** "that", "it" in {@link Item#reference()}. */
    static final int THAT = -1;
    private static final int SEARCH_SIZE = 20;
    private static final int MAX_CLEARED_LINES = 15;

    private final BusinessApi api;
    private final ToolGuard guard;
    private final ShoppingTools shoppingTools;
    private final JsonMapper jsonMapper;

    public ShoppingCommands(BusinessApi api, ToolGuard guard, ShoppingTools shoppingTools, JsonMapper jsonMapper) {
        this.api = api;
        this.guard = guard;
        this.shoppingTools = shoppingTools;
        this.jsonMapper = jsonMapper;
    }

    // ---- commands -----------------------------------------------------------------------------------------------

    sealed interface Command permits ShowCart, Preview, PlaceOrder, ClearCart, Add, Remove, SetQuantity, Search,
            PriceSearch, Reply {
    }

    /**
     * "do you have basmati rice", "which biscuits do you have", "find paneer", "atta irukka".
     *
     * @param all the customer asked for the whole list ("show me all available rice varieties", "list all dals",
     *            "what rice varieties do you have"): every different product is listed, not only the first few
     */
    record Search(String what, boolean all) implements Command {

        Search(String what) {
            this(what, false);
        }
    }

    /** What a price answer names first. */
    enum PriceOrder {
        /** "the cheapest milk". */
        CHEAPEST,
        /** "the most expensive ghee". */
        DEAREST,
        /** "ghee under ₹400", "how much is ghee": a list, cheapest first. */
        LIST
    }

    /**
     * "the cheapest milk", "any ghee under ₹400", "rice between ₹100 and ₹200", "the most expensive ghee", "how much is
     * ghee"; with {@code add}: "add 2 of the cheapest milk".
     *
     * @param min   the lowest price in rupees (included), or null
     * @param max   the highest price in rupees (included), or null
     * @param add   the product to add (its count or weight), or null for a question
     */
    record PriceSearch(String what, BigDecimal min, BigDecimal max, PriceOrder order, Item add) implements Command {

        boolean bounded() {
            return min != null || max != null;
        }
    }

    record ShowCart() implements Command {
    }

    record Preview() implements Command {
    }

    record PlaceOrder() implements Command {
    }

    record ClearCart() implements Command {
    }

    record Add(List<Item> items) implements Command {
    }

    /** {@code item.counted()}: take that many off; otherwise remove the whole line. */
    record Remove(Item item) implements Command {
    }

    record SetQuantity(String what, int quantity) implements Command {
    }

    /** A bare reply to this layer's last question: an option ("the second one"), a number ("2") or a yes. */
    record Reply(Integer ordinal, Integer number, boolean yes) implements Command {
    }

    /**
     * One product the customer named.
     *
     * @param count     packs asked for (1 without a number)
     * @param counted   a number was given ("2 rice", not "rice")
     * @param packUnit  the number came with a pack word ("2 packets", "2 pcs")
     * @param amount    a weight in grams or a volume in millilitres ("a kg" = 1000), else null
     * @param volume    {@code amount} is a volume
     * @param what      the product words; null for a reference
     * @param reference {@link #THAT}, 0 = "the first one" …, {@link #LAST}; null when the product is named
     */
    record Item(int count, boolean counted, boolean packUnit, BigDecimal amount, boolean volume, String what,
                Integer reference) {

        Item named(String name) {
            return new Item(count, counted, packUnit, amount, volume, name, null);
        }
    }

    // ---- parsing ------------------------------------------------------------------------------------------------

    private static final Map<String, Integer> NUMBER_WORDS = Map.ofEntries(Map.entry("a", 1), Map.entry("an", 1),
            Map.entry("one", 1), Map.entry("two", 2), Map.entry("three", 3), Map.entry("four", 4),
            Map.entry("five", 5), Map.entry("six", 6), Map.entry("seven", 7), Map.entry("eight", 8),
            Map.entry("nine", 9), Map.entry("ten", 10), Map.entry("a couple of", 2), Map.entry("couple of", 2));
    private static final String NUMBER_WORD = "a couple of|couple of|an|a|one|two|three|four|five|six|seven|eight|nine"
            + "|ten|some";
    private static final String PACK_UNITS = "packets?|packs?|pkts?|bottles?|pieces?|pcs|pc|boxes|box|bags?|cans?"
            + "|tins?|jars?|bunch(?:es)?|units?|nos|items?|loaf|loaves|trays?|sachets?|cartons?";
    private static final String MEASURE_UNITS = "kgs?|kilos?|kilograms?|gms?|grams?|g|ltrs?|litres?|liters?|l|ml";
    private static final String UNITS = PACK_UNITS + "|" + MEASURE_UNITS;
    private static final Set<String> WEIGHT_KILO = Set.of("kg", "kgs", "kilo", "kilos", "kilogram", "kilograms");
    private static final Set<String> VOLUME_LITRE = Set.of("l", "ltr", "ltrs", "litre", "litres", "liter", "liters");

    private static final Pattern LEADING_FILLER = Pattern.compile("^(?:please|pls|plz|kindly|ok|okay|yes|yeah|yep"
            + "|sure|also|and|then|now|just|hey|hi|hello|great|thanks|thank you|can you|could you|would you|will you"
            + "|can u|could u|i want to|i'd like to|i would like to|let's|lets|go ahead and)[ ,]+");
    private static final Pattern TRAILING_FILLER = Pattern.compile("[ ,]+(?:please|pls|plz|thanks|thank you|now"
            + "|for me|too|as well)$");

    private static final Pattern SHOW_CART = Pattern.compile("^(?:(?:show|view|see|check|open|display|list|get)"
            + "(?: me)? )?(?:my |the )?(?:cart|basket)(?: items| contents)?$"
            + "|^what(?:'s| is| do i have| have i got) in (?:my |the )?(?:cart|basket)$"
            + "|^(?:show|list)(?: me)? (?:what'?s|what is|the items) in (?:my |the )?(?:cart|basket)$");
    private static final Pattern PREVIEW = Pattern.compile("^(?:checkout|check out|go to checkout"
            + "|preview(?: my| the)? order|(?:my |the )?(?:cart |order )?total"
            + "|what(?:'s| is)(?: my| the)? (?:cart |order )?total"
            + "|(?:what(?:'s| is| are) the )?delivery (?:fee|charge|charges|cost))$");
    private static final Pattern PREVIEW_HOW_MUCH = Pattern.compile("^how much\\b.*\\b(?:cart|order|delivery|total"
            + "|altogether|everything|in all|overall|bill)\\b.*$");
    private static final Pattern PLACE_ORDER = Pattern.compile("^(?:place|confirm|submit|make|finish|complete)"
            + "(?: my| the| this)? order$|^order (?:it|them|everything)$|^(?:buy|order) (?:it |them )?now$"
            + "|^check ?out and (?:pay|order)$");
    private static final Pattern CLEAR_CART = Pattern.compile("^(?:empty|clear|reset)(?: my| the)? (?:cart|basket)$"
            + "|^(?:remove|delete) (?:everything|all(?: the)? items|all)(?: (?:from|in)(?: my| the)? (?:cart|basket))?$");
    private static final Pattern REMOVE = Pattern.compile("^(?:remove|delete|drop|take out|take off)(?: the| my)? "
            + "(.+?)(?: (?:from|in|out of)(?: my| the)? (?:cart|basket))?$");
    private static final Pattern SET_QUANTITY = Pattern.compile("^(?:set|change|make|update)(?: the| my)? (.+?)"
            + "(?: quantity| qty| count)? to (\\d{1,2}|" + NUMBER_WORD + ")$");
    private static final Pattern ADD = Pattern.compile("^(?:add|put|include|throw in|get me|buy)(?: me)? (.+?)"
            + "(?: (?:to|in|into|on)(?: my| the)? (?:cart|basket))?$");
    /** "basmati rice 2 add pannu" (Tamil), "rice add karo" (Hindi), "2 milk add". */
    private static final Pattern ADD_SUFFIX = Pattern.compile("^(.+?) add(?: (?:pannu|pannunga|pannu da|panni|karo"
            + "|kar do|kardo|kar dena|cheyyi|cheyandi|maadi|maadu))?(?: (?:to|in)(?: my| the)? (?:cart|basket))?$");

    /** Product questions: the products are looked up, not remembered (the model invented "Jalebi Biscuits"). */
    private static final Pattern SEARCH = Pattern.compile("^(?:do you have|do u have|have you got|you have|is there"
            + "|are there|got)(?: any)? (.+?)(?: available| in stock)?$"
            + "|^(?:which|what) (.+?) (?:do you have|do u have|have you got|are there|are available|you have"
            + "|do you sell)$"
            + "|^(?:show(?: me)?|list(?: out)?|display|find(?: me)?|search(?: for)?|look for|looking for)(?: some| any)?"
            + " (.+)$"
            + "|^(.+?) (?:irukka|irukkaa|iruka|hai kya|milega|available)$");
    /** The whole list asked for: "all (the) (available) …", "every …", "all kinds of …" (2026-11-21). */
    private static final Pattern ALL_OF = Pattern.compile("^(?:all|every)(?: of)?(?: the| your)?(?: available| different)?"
            + "(?: (?:kinds?|types?|varieties|variety|sorts?|brands?) of)?(?: the)? ");
    private static final Pattern KINDS_OF = Pattern.compile("^(?:(?:the|available|different) )*(?:kinds?|types?"
            + "|varieties|variety|sorts?|brands?) of (?:the )?");
    private static final Pattern KINDS = Pattern.compile(" (?:varieties|variety|kinds|types|brands|options)$");
    private static final Pattern AVAILABLE = Pattern.compile("^(?:available|different) | (?:available|in stock"
            + "|you have|you sell|you've got)$");
    /** What a product search is not for: the customer's own things, restaurants and their food. */
    private static final Pattern NOT_A_PRODUCT = Pattern.compile("\\b(?:orders?|cart|basket|payments?|refunds?"
            + "|returns?|complaints?|address(?:es)?|account|profile|deliver(?:y|ies)|restaurants?|menus?|dish(?:es)?"
            + "|meals?|food|biryani|dosa|idli|parotta|pizza|burger|stores?|shops?)\\b");

    private static final Pattern ITEM_LEADING = Pattern.compile("^(?:(\\d{1,4}(?:\\.\\d{1,2})?)\\s*|(" + NUMBER_WORD
            + ")\\s+)?(?:x\\s+)?(?:(" + UNITS + ")\\.?\\s+)?(?:of\\s+)?(.+)$");
    private static final Pattern ITEM_TRAILING = Pattern.compile("^(.+?)\\s+(?:x\\s*)?(\\d{1,4}(?:\\.\\d{1,2})?)"
            + "(?:\\s*(" + UNITS + ")\\.?)?$");
    private static final Pattern ITEM_SPLIT = Pattern.compile("\\s*(?:,|\\band\\b|&|\\bplus\\b)\\s*");

    /** Conditions, comparisons and questions: the model's job. */
    private static final Pattern COMPLEX = Pattern.compile("\\b(?:cheap\\w*|costly|best|top|under|below|above|over"
            + "|less|more|between|than|rs|rupees?|category|section|or|if|instead|replace|except|not|which|what|where"
            + "|when|why|how|any|from|offer|discount|deal|dozens?|half|similar|like)\\b|₹|%|\\?");
    // Price questions. A price is a number with or without ₹ / rs / rupees, never followed by a unit ("under 5 kg").
    private static final String AMOUNT = "(?:₹\\s*|rs\\.?\\s*|inr\\s*|rupees?\\s*)?"
            + "(\\d{1,3}(?:,\\d{3})+(?:\\.\\d{1,2})?|\\d{1,6}(?:\\.\\d{1,2})?)"
            + "(?:\\s*(?:₹|/-|rs\\b\\.?|inr\\b|rupees?\\b|bucks\\b))?(?![\\d.,])(?!\\s*(?:" + UNITS + ")\\b)";
    private static final Pattern PRICE_BETWEEN = Pattern.compile("\\b(?:between|from)\\s+" + AMOUNT
            + "\\s*(?:and|to|-)\\s*" + AMOUNT);
    /** "₹100 to ₹200", "rs 100-200". */
    private static final Pattern PRICE_RANGE = Pattern.compile("(?:₹|\\brs\\.?)\\s*(\\d{1,6})\\s*(?:to|-)\\s*"
            + AMOUNT);
    private static final Pattern PRICE_MAX = Pattern.compile("\\b(?:under|below|less than|lesser than|lower than|within"
            + "|upto|up to|max(?:imum)?(?: of)?|at most|cheaper than|for less than)\\s*" + AMOUNT);
    private static final Pattern PRICE_MIN = Pattern.compile("\\b(?:above|over|more than|greater than|higher than"
            + "|at least|min(?:imum)?(?: of)?|costlier than|dearer than|pricier than)\\s*" + AMOUNT);
    private static final Pattern PRICE_DEAREST = Pattern.compile("\\b(?:most expensive|costliest|priciest"
            + "|highest[- ]priced?|highest cost|most costly|dearest)\\b");
    private static final Pattern PRICE_CHEAPEST = Pattern.compile("\\b(?:cheapest|lowest[- ]priced?|lowest cost"
            + "|least expensive|least costly|most affordable|most economical)\\b");
    private static final Pattern PRICE_CHEAP = Pattern.compile("\\b(?:cheap|affordable|budget|inexpensive"
            + "|low[- ]priced?|low[- ]cost|economical)\\b");
    private static final Pattern PRICE_WORDS = Pattern.compile("\\b(?:prices?|rates?|costs?|how much)\\b");
    /** The question around the product's name: "what's the …", "do you have …", "price of …". */
    private static final Pattern PRICE_LEADING = Pattern.compile("^(?:how much (?:is|are|does|do|for|will|would)"
            + "|how much|what(?:'s| is| are| does| do| will)?|whats|which(?: one)?(?: is| are)?|show(?: me)?"
            + "|find(?: me)?|search(?: for)?|look(?:ing)? for|i'?m looking for|list|tell me|give me|i want|i need"
            + "|do you have|do u have|have you got|is there|are there|got|you have|any|some|the|a|an|your|me"
            + "|(?:prices?|costs?|rates?) (?:of|for)|of|for)(?:\\s+|$)");
    private static final Pattern PRICE_TRAILING = Pattern.compile("(?:^|\\s+)(?:do you have|do u have|you have"
            + "|you've got|have you got|you got|do you sell|you sell|available|in stock|on sale|for sale|options?|ones?"
            + "|brands?|varieties|variety|kinds?|types?|products?|items?|now|today|here|nearby|near me|prices?|rates?"
            + "|costs?|cost me|costing|priced|that costs?|which costs?|that is|which is|for|is|are|be|there"
            + "|with you|in your store)$");
    /** Left over around the product's name: another condition, a comparison, a question, not a product. */
    private static final Pattern PRICE_RESIDUE = Pattern.compile("\\b(?:or|and|from|in|at|with|without|category"
            + "|section|best|top|rated|ratings?|reviews?|offers?|discounts?|deals?|per|than|if|not|no|except|instead"
            + "|similar|like|better|compare|vs|versus|which|what|where|when|why|how|who|cheap\\w*|expensive|costly"
            + "|prices?|costs?|rates?|between|under|below|above|over|less|more|most|least|one|ones|it|that|this"
            + "|those|these|i|me|my|we|our|us|you|your|u|do|does|did|can|could|would|will|should|need|want|buy"
            + "|order|spend|spent|pay|paid|make|cook|recipe|healthy|good|many|much)\\b|[\\d₹%?&,/+]");
    /** Not one kind of product: "anything under ₹50", "how many products under ₹50" (the model counts those). */
    private static final Pattern PRICE_GENERIC = Pattern.compile("(?:products?|items?|things?|stuff|anything"
            + "|something|everything|groceries|grocery|all)");

    /** What "remove" or "set" can never mean in the cart. */
    private static final Pattern NOT_A_CART_LINE = Pattern.compile("\\b(?:orders?|address(?:es)?|account|complaints?"
            + "|returns?|payments?|cards?|conversation|chat|history)\\b");

    private static final Pattern REFERENCE = Pattern.compile("^(?:that|it|this|them|those|these|that one|this one"
            + "|same|the same(?: one)?)$");
    private static final Pattern ORDINAL = Pattern.compile("^(?:the )?(?:(first|second|third|fourth|fifth|last)"
            + "|(\\d)(?:st|nd|rd|th))(?: one)?$|^(?:number|no\\.?|option|#) ?(\\d)$");
    private static final Pattern REPLY_NUMBER = Pattern.compile("^(\\d{1,2}|one|two|three|four|five|six|seven|eight"
            + "|nine|ten)(?: (?:" + PACK_UNITS + "|of them|of those))?$");
    private static final Pattern REPLY_YES = Pattern.compile("^(?:yes|yeah|yep|ok|okay|sure|fine|go ahead"
            + "|that one|this one|that|it)$");
    private static final List<String> ORDINAL_WORDS = List.of("first", "second", "third", "fourth", "fifth");

    /** The message lower-cased, without politeness, fillers and trailing punctuation. */
    static String normalize(String message) {
        String text = message.toLowerCase(Locale.ROOT).replace('’', '\'').replaceAll("[“”\"]", "")
                .replaceAll("\\s+", " ").strip();
        String before;
        do {
            before = text;
            text = text.replaceAll("[.!?,]+$", "").strip();
            text = LEADING_FILLER.matcher(text).replaceFirst("").strip();
            text = TRAILING_FILLER.matcher(text).replaceFirst("").strip();
        } while (!text.equals(before));
        return text;
    }

    /** The command in {@code message}, or null when it is not one of the simple commands. */
    static Command parse(String message) {
        if (message == null || message.isBlank() || message.length() > 160) {
            return null;
        }
        String text = normalize(message);                     // "can you add rice?" → "add rice"
        if (text.isEmpty()) {
            return null;
        }
        if (SHOW_CART.matcher(text).matches()) {
            return new ShowCart();
        }
        if (PREVIEW.matcher(text).matches() || PREVIEW_HOW_MUCH.matcher(text).matches()) {
            return new Preview();
        }
        if (PLACE_ORDER.matcher(text).matches()) {
            return new PlaceOrder();
        }
        if (CLEAR_CART.matcher(text).matches()) {
            return new ClearCart();
        }
        Matcher set = SET_QUANTITY.matcher(text);
        if (set.matches()) {
            String what = cleanWhat(set.group(1));
            return what == null || COMPLEX.matcher(what).find() || NOT_A_CART_LINE.matcher(what).find()
                    || REFERENCE.matcher(what).matches() ? null : new SetQuantity(what, number(set.group(2)));
        }
        Matcher remove = REMOVE.matcher(text);
        if (remove.matches()) {
            String rest = remove.group(1);
            if (COMPLEX.matcher(rest).find() || NOT_A_CART_LINE.matcher(rest).find()) {
                return null;
            }
            Item item = parseItem(rest);
            return item == null || item.amount() != null || item.what() == null ? null : new Remove(item);
        }
        Matcher add = ADD.matcher(text);
        if (!add.matches()) {
            add = ADD_SUFFIX.matcher(text);
        }
        if (add.matches()) {
            String rest = add.group(1);
            if (COMPLEX.matcher(rest).find()) {
                return priceAdd(rest);                        // "add the cheapest milk"; other conditions: the model
            }
            List<Item> items = new ArrayList<>();
            for (String part : ITEM_SPLIT.split(rest)) {
                if (part.isBlank()) {
                    continue;
                }
                Item item = parseItem(part);
                if (item == null) {
                    return null;
                }
                items.add(item);
            }
            return items.isEmpty() || items.size() > MAX_ITEMS ? null : new Add(items);
        }
        PriceSearch price = priceQuestion(text);
        if (price != null) {
            return price;
        }
        Matcher search = SEARCH.matcher(text);
        if (search.matches()) {
            String rest = Stream.of(search.group(1), search.group(2), search.group(3), search.group(4))
                    .filter(Objects::nonNull).findFirst().orElse("");
            if (COMPLEX.matcher(rest).find() || NOT_A_PRODUCT.matcher(rest).find()
                    || rest.matches("(?:do|you|u|i|we|it|that|this|anything|something|everything|stuff|things?)")) {
                return null;                                   // "what do you have": not a product
            }
            Search asked = searchFor(rest);                    // "all available rice varieties" → rice, all
            Item item = asked == null ? null : parseItem(asked.what());   // "atta 5 kilo" → atta
            return item == null || item.what() == null ? null : new Search(item.what(), asked.all());
        }
        return reply(text);
    }

    /**
     * A product question's words without the "whole list" wording, and whether there was some: "all available rice
     * varieties" → rice (all), "rice varieties", "all kinds of dal", "brands of ghee", "every ghee you have". Live
     * 2026-11-21: "Show me all available rice varieties" searched those four words, found nothing and went to the
     * model, which listed 5 of the 7 kinds of rice. Null when no product is named ("all", "all products").
     */
    static Search searchFor(String rest) {
        String what = rest.strip();
        boolean all = false;
        String before;
        do {
            before = what;
            Matcher found;
            if ((found = ALL_OF.matcher(what)).find() || (found = KINDS_OF.matcher(what)).find()) {
                all = true;
                what = what.substring(found.end()).strip();
            }
            if ((found = KINDS.matcher(what)).find()) {
                all = true;
                what = what.substring(0, found.start()).strip();
            }
            what = AVAILABLE.matcher(what).replaceAll("").strip();
        } while (!what.equals(before));
        return what.isEmpty() || PRICE_GENERIC.matcher(what).matches()
                || what.matches("(?:kinds?|types?|varieties|variety|sorts?|brands?|options)") ? null : new Search(what, all);
    }

    /**
     * A price question in {@code text} (normalized), or null: a price bound ("under ₹400", "between ₹100 and ₹200"),
     * the cheapest or the most expensive, or a price ("how much is ghee", "milk price"), around one product's name and
     * nothing else ("the best rice under ₹100", "cheapest milk from Adyar", "anything under ₹50": the model).
     */
    static PriceSearch priceQuestion(String text) {
        if (text.length() > 120) {
            return null;
        }
        String rest = " " + text + " ";
        BigDecimal min = null;
        BigDecimal max = null;
        Matcher between = PRICE_BETWEEN.matcher(rest);
        Matcher range = PRICE_RANGE.matcher(rest);
        Matcher both = between.find() ? between : range.find() ? range : null;
        if (both != null) {
            BigDecimal first = amount(both.group(1));
            BigDecimal second = amount(both.group(2));
            min = first.min(second);
            max = first.max(second);
            rest = cut(rest, both);
        } else {
            Matcher atMost = PRICE_MAX.matcher(rest);
            if (atMost.find()) {
                max = amount(atMost.group(1));
                rest = cut(rest, atMost);
            }
            Matcher atLeast = PRICE_MIN.matcher(rest);
            if (atLeast.find()) {
                min = amount(atLeast.group(1));
                rest = cut(rest, atLeast);
            }
        }
        if ((min != null && min.signum() <= 0) || (max != null && max.signum() <= 0)
                || (min != null && max != null && min.compareTo(max) > 0)) {
            return null;
        }
        PriceOrder order = null;
        for (Map.Entry<Pattern, PriceOrder> words : List.of(Map.entry(PRICE_DEAREST, PriceOrder.DEAREST),
                Map.entry(PRICE_CHEAPEST, PriceOrder.CHEAPEST), Map.entry(PRICE_CHEAP, PriceOrder.LIST))) {
            Matcher found = words.getKey().matcher(rest);
            if (found.find()) {
                order = words.getValue();
                rest = cut(rest, found);
                break;
            }
        }
        boolean asksPrice = PRICE_WORDS.matcher(rest).find();
        if (min == null && max == null && order == null && !asksPrice) {
            return null;
        }
        String what = priceProduct(rest);
        return what == null ? null : new PriceSearch(what, min, max, order == null ? PriceOrder.LIST : order, null);
    }

    /** "the cheapest milk" (after "add"), "2 packets of the cheapest milk", "milk under ₹50"; null otherwise. */
    static PriceSearch priceAdd(String rest) {
        Matcher leading = ITEM_LEADING.matcher(rest);
        String count = "";
        String product = rest;
        if (leading.matches() && (leading.group(1) != null || leading.group(2) != null || leading.group(3) != null)) {
            count = rest.substring(0, leading.start(4));       // "2 packets of "
            product = leading.group(4);
        }
        PriceSearch price = priceQuestion(product);
        if (price == null || (price.order() == PriceOrder.LIST && !price.bounded())) {
            return null;                                       // "add cheap snacks": which ones?
        }
        Item item = parseItem(count + price.what());
        return item == null || item.what() == null ? null
                : new PriceSearch(price.what(), price.min(), price.max(), price.order(), item);
    }

    /** The product's name in what is left of a price question, or null when something else is left. */
    static String priceProduct(String rest) {
        String what = rest.replaceAll("'s\\b", "").replaceAll("\\s+", " ").strip();
        String before;
        do {
            before = what;
            what = PRICE_LEADING.matcher(what).replaceFirst("").strip();
            what = PRICE_TRAILING.matcher(what).replaceFirst("").strip();
        } while (!what.equals(before));
        if (what.isEmpty() || what.length() > 40 || what.split(" ").length > 4 || PRICE_RESIDUE.matcher(what).find()
                || NOT_A_PRODUCT.matcher(what).find() || PRICE_GENERIC.matcher(what).matches()
                || REFERENCE.matcher(what).matches()) {
            return null;
        }
        return what;
    }

    private static BigDecimal amount(String text) {
        return new BigDecimal(text.replace(",", ""));
    }

    /** {@code text} without what {@code found} matched. */
    private static String cut(String text, Matcher found) {
        return text.substring(0, found.start()) + " " + text.substring(found.end());
    }

    /** A bare reply ("the second one", "2", "yes"), or null. */
    static Reply reply(String text) {
        Matcher ordinal = ORDINAL.matcher(text);
        if (ordinal.matches()) {
            if (ordinal.group(1) != null) {
                return new Reply("last".equals(ordinal.group(1)) ? LAST : ORDINAL_WORDS.indexOf(ordinal.group(1)) + 1,
                        null, false);
            }
            return new Reply(Integer.parseInt(ordinal.group(2) != null ? ordinal.group(2) : ordinal.group(3)), null,
                    false);
        }
        Matcher number = REPLY_NUMBER.matcher(text);
        if (number.matches()) {
            return new Reply(null, number(number.group(1)), false);
        }
        return REPLY_YES.matcher(text).matches() ? new Reply(null, null, true) : null;
    }

    /** "2 packets of basmati rice", "a kg of tomatoes", "atta 5 kilo", "the second one"; null when unclear. */
    static Item parseItem(String raw) {
        String text = raw.strip();
        if (text.isEmpty() || text.length() > 60) {
            return null;
        }
        String numberText = null;
        String unit = null;
        String what = text;
        Matcher leading = ITEM_LEADING.matcher(text);
        Matcher trailing = ITEM_TRAILING.matcher(text);
        if (leading.matches() && (leading.group(1) != null || leading.group(2) != null || leading.group(3) != null)) {
            numberText = leading.group(1) != null ? leading.group(1) : leading.group(2);
            unit = leading.group(3);
            what = leading.group(4);
        } else if (trailing.matches()) {
            what = trailing.group(1);
            numberText = trailing.group(2);
            unit = trailing.group(3);
        }
        BigDecimal number = numberText == null || "some".equals(numberText) ? null
                : NUMBER_WORDS.containsKey(numberText) ? BigDecimal.valueOf(NUMBER_WORDS.get(numberText))
                : new BigDecimal(numberText);
        String cleaned = cleanWhat(what);
        if (cleaned == null || cleaned.matches("[\\d .]+")) {
            return null;                                       // "add 2": two of what?
        }
        Integer reference = reference(cleaned);
        String named = reference == null ? cleaned : null;
        if (unit != null && (WEIGHT_KILO.contains(unit) || VOLUME_LITRE.contains(unit) || unit.matches(MEASURE_UNITS))) {
            BigDecimal base = number == null ? BigDecimal.ONE : number;
            boolean volume = VOLUME_LITRE.contains(unit) || "ml".equals(unit);
            BigDecimal factor = WEIGHT_KILO.contains(unit) || VOLUME_LITRE.contains(unit) ? BigDecimal.valueOf(1000)
                    : BigDecimal.ONE;
            return new Item(1, false, false, base.multiply(factor), volume, named, reference);
        }
        if (number != null && number.stripTrailingZeros().scale() > 0) {
            return null;                                       // "1.5 packets"
        }
        int count = number == null ? 1 : number.intValue();
        if (count < 1) {
            return null;
        }
        return new Item(count, number != null, unit != null, null, false, named, reference);
    }

    /** The product words without articles ("of the rice" → "rice"); null when nothing is left. */
    private static String cleanWhat(String what) {
        if (what == null) {
            return null;
        }
        String text = what.strip().replaceAll("^(?:of |the |some |my )+", "").strip();
        return text.isEmpty() ? null : text;
    }

    /** {@link #THAT}, an index (0 = the first one), {@link #LAST}; null when {@code what} names a product. */
    private static Integer reference(String what) {
        if (REFERENCE.matcher(what).matches()) {
            return THAT;
        }
        Reply ordinal = reply(what);
        if (ordinal != null && ordinal.ordinal() != null) {
            return ordinal.ordinal() == LAST ? LAST : ordinal.ordinal() - 1;
        }
        return null;
    }

    private static int number(String text) {
        return NUMBER_WORDS.containsKey(text) ? NUMBER_WORDS.get(text) : Integer.parseInt(text);
    }

    // ---- handling -------------------------------------------------------------------------------------------------

    /**
     * The answer when {@code message} is one of the simple commands (its tools already ran through the guard), or
     * empty for the model.
     *
     * @param lastNote the app's note after the previous answer (its tool results, maybe a question of this layer)
     * @param notes    the app's notes of the conversation so far, oldest first (tool results: the products shown)
     */
    public Optional<String> handle(ToolTurn turn, String message, String lastNote, List<String> notes) {
        Command command = parse(message);
        if (command == null) {
            return Optional.empty();
        }
        JsonNode choice = choice(lastNote);
        String answer = switch (command) {
            case Reply reply -> choice == null ? null : answerReply(turn, reply, choice, notes);
            case ShowCart ignored -> showCart(turn);
            case Preview ignored -> preview(turn);
            case PlaceOrder ignored -> placeOrder(turn);
            case ClearCart ignored -> clearCart(turn);
            case Add add -> add(turn, add.items(), choice, notes);
            case Remove remove -> changeLine(turn, remove.item().what(),
                    remove.item().counted() ? -remove.item().count() : 0, false);
            case SetQuantity set -> changeLine(turn, set.what(), set.quantity(), true);
            case Search search -> search(turn, search.what(), search.all());
            case PriceSearch price -> price.add() == null ? prices(turn, price) : addByPrice(turn, price);
        };
        return Optional.ofNullable(answer);
    }

    // ---- product questions ----------------------------------------------------------------------------------------

    /**
     * "do you have basmati rice": searchProducts through the guard (its chip, its link to all results, and the
     * products in the memory for "add 2 of that"), answered with the distinct products found. Nothing found → the
     * model (it may mean a restaurant dish, or other words). {@code all} ("show me all available rice varieties"):
     * every different product, up to {@link #MAX_ALL_OPTIONS}, from catalog-query's largest page. Each line quotes the
     * cheapest store ({@link #quoted}); "add 2 of the first one" adds that store's listing.
     */
    private String search(ToolTurn turn, String what, boolean all) {
        int pageSize = all ? PRICE_SEARCH_SIZE : SEARCH_SIZE;
        List<String> stores = shoppingTools.storeScope(turn.http()).vendorIds();
        List<JsonNode> found = search(turn, what, stores, pageSize);
        if (found == null || found.isEmpty()) {
            return null;
        }
        JsonNode result = run(turn, "searchProducts", Map.of("query", what));
        String failed = failure(result);
        if (failed != null) {
            return "I couldn't search right now: " + failed;
        }
        List<JsonNode> products = Json.list(result, "products");
        if (products.isEmpty()) {
            return null;
        }
        // The search also matches descriptions and category names ("ghee" → Halwa, Ladoo; "biscuits" → Dog Biscuits):
        // the products the words mean when there are (Britannia Marie Gold for "biscuits"), else what it found.
        List<JsonNode> matching = relevant(turn, found, what);
        List<JsonNode> listed = matching.isEmpty() ? found : matching;
        int limit = all ? MAX_ALL_OPTIONS : MAX_OPTIONS;
        Map<String, List<JsonNode>> byName = new LinkedHashMap<>();
        listed.forEach(card -> {
            if (byName.containsKey(Json.text(card, "name")) || byName.size() < limit) {
                byName.computeIfAbsent(Json.text(card, "name"), name -> new ArrayList<>()).add(card);
            }
        });
        List<JsonNode> quotes = byName.values().stream().map(cards -> quoted(cards, stores)).toList();
        // "add 2 of the second one" means the second product listed here, from the store whose price was given
        rememberShown(turn, quotes);
        List<String> lines = new ArrayList<>();
        for (JsonNode listing : quotes) {
            String unit = Json.text(listing, "unitLabel");
            lines.add((price(listing) == null ? Json.text(listing, "name") + (unit == null ? "" : " (" + unit + ")")
                    : priceLine(listing)) + (Json.bool(listing, "inStock") ? "" : " (out of stock)"));
        }
        // the search page lists each store's offer: its count, when it differs from the different products
        int total = Objects.requireNonNullElse(Json.integer(result, "listings"),
                Objects.requireNonNullElse(Json.integer(result, "totalFound"), products.size()));
        if (all) {
            // every one: the page held every listing and each different product is listed
            boolean complete = found.size() < pageSize
                    && byName.size() == listed.stream().map(card -> Json.text(card, "name")).distinct().count();
            String intro = byName.size() == 1 ? "There's 1 product for “" + what + "”:"
                    : complete ? "Here are all " + byName.size() + " different products for “" + what + "”:"
                    : "Here are " + byName.size() + " different products for “" + what + "”:";
            return intro + numbered(lines) + "\nSay “add 2 of the first one” to add one"
                    + (complete ? "." : ", or open the search page to see them all.");
        }
        return "Yes, here's what I found for “" + what + "”:" + numbered(lines)
                + "\nSay “add 2 of the first one” to add one" + (total > products.size()
                ? ", or open the search page to see all " + total + " results." : ".");
    }

    // ---- price questions ------------------------------------------------------------------------------------------

    /** Products per price search: catalog-query's largest page (the search also matches descriptions). */
    private static final int PRICE_SEARCH_SIZE = 50;

    /**
     * "the cheapest milk", "any ghee under ₹400", "how much is ghee": searchProducts through the guard (its chip links
     * to the search page with the same filters), answered from the search's products whose names have the words, one
     * listing per product (in stock, the lowest price, then the nearest store). The search also matches descriptions:
     * sorted by price, Halwa and Ladoo ("made with ghee") came before Ghee, and the model then answered "There is no
     * ghee under ₹400. The cheapest ghee available is ₹340.00". The products shown are remembered with their listings,
     * so "add 2 of the first one" adds the one whose price was given.
     */
    private String prices(ToolTurn turn, PriceSearch question) {
        ShoppingTools.StoreScope scope = shoppingTools.storeScope(turn.http());
        if (scope.noneDeliver()) {
            return "No grocery store delivers to " + scope.place() + " yet, so there are no prices to show there.";
        }
        List<String> stores = scope.vendorIds();
        boolean dearest = question.order() == PriceOrder.DEAREST;
        String where = scope.place() == null ? "" : " in the stores that deliver to " + scope.place();
        List<JsonNode> cards = priced(turn, question.what(), stores, question.min(), question.max(), dearest);
        if (cards == null) {
            return "I couldn't look up prices right now. Please try again in a moment.";
        }
        List<JsonNode> matching = within(relevant(turn, cards, question.what()), question.min(), question.max());
        if (matching.isEmpty()) {
            // Nothing with these words between the bounds: what there is nearest to them. No such product: the model.
            return question.bounded() ? outsideBounds(turn, question, stores, where) : null;
        }
        String failed = failure(run(turn, "searchProducts", toolArgs(question)));
        if (failed != null) {
            return "I couldn't search right now: " + failed;
        }
        String what = "“" + question.what() + "”" + priceRange(question.min(), question.max(), true);
        List<JsonNode> ranked = ranked(matching, stores, dearest);
        if (ranked.isEmpty()) {
            return capitalize(what) + " " + isOrAre(question.what()) + " out of stock" + where + " right now.";
        }
        List<JsonNode> shown = ranked.stream().limit(MAX_OPTIONS).toList();
        rememberShown(turn, shown);
        StringBuilder answer = new StringBuilder();
        boolean several = plural(question.what());
        if (question.order() == PriceOrder.LIST) {
            answer.append(question.bounded() ? (several ? "Yes, here are " : "Yes, here's ") + what
                    + ", cheapest first:" : "Prices for " + what + ", cheapest first:");
        } else {
            answer.append("The ").append(dearest ? "most expensive " : "cheapest ").append(what)
                    .append(several ? " are " : " is ").append(priceSentence(shown.getFirst())).append('.');
            if (shown.size() > 1) {
                answer.append(dearest ? "\nMost expensive first:" : "\nCheapest first:");
            }
        }
        if (question.order() == PriceOrder.LIST || shown.size() > 1) {
            answer.append(numbered(shown.stream().map(ShoppingCommands::priceLine).toList()));
        }
        answer.append(shown.size() == 1 ? "\nSay “add 2 of that” to add it"
                : "\nSay “add 2 of the first one” to add one");
        return answer.append(ranked.size() > shown.size() ? ", or open the search page to see them all." : ".")
                .toString();
    }

    /**
     * Nothing with the words between the price bounds: the nearest in stock instead (the cheapest above a highest
     * price, the most expensive below a lowest one); null when there is no such product at all.
     */
    private String outsideBounds(ToolTurn turn, PriceSearch question, List<String> stores, String where) {
        boolean dearestFirst = question.max() == null;            // only a lowest price: the most expensive below it
        List<JsonNode> cards = priced(turn, question.what(), stores, null, null, dearestFirst);
        List<JsonNode> ranked = cards == null ? List.of() : ranked(relevant(turn, cards, question.what()), stores,
                dearestFirst);
        List<JsonNode> nearest = new ArrayList<>();
        if (question.min() != null) {
            ranked.stream().filter(card -> price(card).compareTo(question.min()) < 0)
                    .max(Comparator.<JsonNode, BigDecimal>comparing(ShoppingCommands::price)).ifPresent(nearest::add);
        }
        if (question.max() != null) {
            ranked.stream().filter(card -> price(card).compareTo(question.max()) > 0)
                    .min(Comparator.<JsonNode, BigDecimal>comparing(ShoppingCommands::price)).ifPresent(nearest::add);
        }
        if (nearest.isEmpty()) {
            return null;
        }
        String failed = failure(run(turn, "searchProducts", toolArgs(question)));
        if (failed != null) {
            return "I couldn't search right now: " + failed;
        }
        rememberShown(turn, nearest);
        String none = "No “" + question.what() + (plural(question.what()) ? "” cost " : "” costs ")
                + priceRange(question.min(), question.max(), false) + where + ".";
        if (nearest.size() > 1) {
            return none + " The nearest are:" + numbered(nearest.stream().map(ShoppingCommands::priceLine).toList())
                    + "\nSay “add 2 of the first one” to add one.";
        }
        JsonNode only = nearest.getFirst();
        boolean above = question.max() != null && price(only).compareTo(question.max()) > 0;
        return none + (above ? " The cheapest is " : " The most expensive is ") + priceSentence(only)
                + ".\nSay “add 2 of that” to add it.";
    }

    /**
     * "add the cheapest milk", "add 2 milk under ₹50": the product the price question means, in the cart's store when
     * the cart has one (a cart holds one store); a list without a cheapest or most expensive asks which one.
     */
    private String addByPrice(ToolTurn turn, PriceSearch question) {
        JsonNode cart = readCart(turn);
        String cartStore = cart == null || Json.list(cart, "lines").isEmpty() ? null : Json.text(cart, "store.id");
        String cartStoreName = cartStore == null ? null : Json.text(cart, "store.name");
        ShoppingTools.StoreScope scope = shoppingTools.storeScope(turn.http());
        if (scope.noneDeliver()) {
            return "No grocery store delivers to " + scope.place() + " yet, so I can't add products there.";
        }
        boolean dearest = question.order() == PriceOrder.DEAREST;
        List<String> stores = cartStore != null ? List.of(cartStore) : scope.vendorIds();
        List<JsonNode> cards = priced(turn, question.what(), stores, question.min(), question.max(), dearest);
        if (cards == null) {
            return "The product search isn't available right now, so I couldn't add " + question.what() + ".";
        }
        List<JsonNode> ranked = ranked(within(relevant(turn, cards, question.what()), question.min(), question.max()),
                stores, dearest);
        String what = "“" + question.what() + "”" + priceRange(question.min(), question.max(), true);
        if (ranked.isEmpty()) {
            if (cartStore != null) {
                List<JsonNode> elsewhere = priced(turn, question.what(), scope.vendorIds(), question.min(),
                        question.max(), dearest);
                List<JsonNode> other = elsewhere == null ? List.of() : ranked(within(relevant(turn, elsewhere,
                        question.what()), question.min(), question.max()), scope.vendorIds(), dearest);
                if (!other.isEmpty()) {
                    return "Your cart has products from " + cartStoreName + ", which doesn't have " + what + " ("
                            + Json.text(other.getFirst(), "vendor.name") + " has " + Json.text(other.getFirst(), "name")
                            + " for " + Money.inr(price(other.getFirst())) + "). A cart holds one store: to order from "
                            + "another store, empty your cart first (say “clear my cart”).";
                }
            }
            return "I couldn't find " + what + " in stock" + (cartStore != null ? " at " + cartStoreName
                    : scope.place() == null ? "" : " in the stores that deliver to " + scope.place()) + ".";
        }
        Item item = question.add();
        if (question.order() == PriceOrder.LIST && ranked.size() > 1) {
            List<JsonNode> shown = ranked.stream().limit(MAX_OPTIONS).toList();
            rememberProductChoice(turn, item, shown.stream().map(card -> Json.text(card, "name")).toList());
            return "Which one would you like? Here" + (plural(question.what()) ? " are " : "'s ") + what
                    + ", cheapest first:"
                    + numbered(shown.stream().map(ShoppingCommands::priceLine).toList())
                    + "\nSay “the first one”, “the second one”…";
        }
        JsonNode chosen = ranked.getFirst();
        String unit = Json.text(chosen, "unitLabel");
        String intro = question.order() == PriceOrder.LIST ? ""
                : "The " + (dearest ? "most expensive " : "cheapest ") + what
                + (cartStore != null ? " at " + cartStoreName : "") + " " + isOrAre(question.what()) + " "
                + Json.text(chosen, "name") + " ("
                + (unit == null ? "" : unit + ", ") + Money.inr(price(chosen)) + ").\n";
        Outcome outcome = addOne(turn, item.named(Json.text(chosen, "name")), cart, cartStore, cartStoreName, scope,
                chosen);
        return intro + switch (outcome) {
            case Added added -> added.text() + "\n" + summary(added.cart());
            case Asked asked -> asked.question();
            case Said said -> said.text();
        };
    }

    /** Products for {@code query} in {@code stores} between the prices (null: none), by price; null when it failed. */
    private List<JsonNode> priced(ToolTurn turn, String query, List<String> stores, BigDecimal min, BigDecimal max,
                                  boolean dearestFirst) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("q", query);
        params.put("size", PRICE_SEARCH_SIZE);
        params.put("vendorId", stores);
        if (min != null) {
            params.put("minPrice", min.toPlainString());
        }
        if (max != null) {
            params.put("maxPrice", max.toPlainString());
        }
        params.put("sort", dearestFirst ? "PRICE_DESC" : "PRICE_ASC");
        return api.get(turn.http(), "/api/search/products", params) instanceof Fetch.Ok ok
                ? Json.list(ok.body(), "content") : null;
    }

    /** The question as the model would have asked it: the tool's chip links to the search page with these filters. */
    private static Map<String, Object> toolArgs(PriceSearch question) {
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("query", question.what());
        if (question.min() != null) {
            args.put("minPrice", question.min());
        }
        if (question.max() != null) {
            args.put("maxPrice", question.max());
        }
        args.put("sort", question.order() == PriceOrder.DEAREST ? "PRICE_DESC" : "PRICE_ASC");
        return args;
    }

    /** Most product details read for one question ({@link #relevant(ToolTurn, List, String)}). */
    private static final int MAX_DESCRIPTION_READS = 8;
    /** Words that ask for pet products: without them, Pet Care's products never count ("Dog Biscuits"). */
    private static final Set<String> PET_WORDS = Set.of("pet", "dog", "cat", "puppy", "kitten");

    /**
     * The products the words mean ({@link #relevant(List, String, Predicate)}), with the description of a product
     * named otherwise in a category of several kinds: the card's {@code shortDescription} (catalog-query since
     * 2026-11-20), or the product's detail when the card has none or a description cut short (one read per product
     * name, at most {@link #MAX_DESCRIPTION_READS}).
     */
    List<JsonNode> relevant(ToolTurn turn, List<JsonNode> cards, String what) {
        List<String> wanted = words(what);
        Map<String, Boolean> described = new HashMap<>();
        int[] reads = {0};
        return relevant(cards, what, card -> described.computeIfAbsent(
                Objects.requireNonNullElse(Json.text(card, "name"), "").toLowerCase(Locale.ROOT),
                name -> describes(turn, card, wanted, reads)));
    }

    /** The product's description has all the words: the card's short one, else its detail ({@code reads} counted). */
    private boolean describes(ToolTurn turn, JsonNode card, List<String> wanted, int[] reads) {
        if (card.has("shortDescription")) {
            String text = Json.text(card, "shortDescription");
            if (text == null) {
                return false;                                 // the product has no description
            }
            if (words(text).containsAll(wanted)) {
                return true;
            }
            if (!text.endsWith("…")) {
                return false;                                 // the whole description, without the words
            }
        }
        if (reads[0] >= MAX_DESCRIPTION_READS) {
            return false;
        }
        reads[0]++;
        return api.get(turn.http(), "/api/catalog/products/" + Json.text(card, "id")) instanceof Fetch.Ok ok
                && words(Json.text(ok.body(), "description")).containsAll(wanted);
    }

    /**
     * The products the words mean, in the cards' order:
     * <ul>
     *   <li>names (or brands) with all the words, preferring names that end in the last word ("milk": Milk, Aavin Full
     *       Cream Milk; not Milk Bread);</li>
     *   <li>the products of a category that is exactly these words ("fruits", "dairy": Dairy Products);</li>
     *   <li>in a category of several kinds with a part that is these words ("Biscuits and Cookies"), a product named
     *       otherwise whose description has them ({@code describedAs}): Britannia Marie Gold, "Light, crisp Marie tea
     *       biscuits".</li>
     * </ul>
     * Never a product whose description alone has the words (Halwa, "made with ghee", for "ghee"), nor the rest of a
     * category of several kinds (chicken in "Eggs, Meat and Seafood" is not an egg, Halwa not a chocolate), nor pet
     * products unless the words ask for a pet (Dog Biscuits for "biscuits").
     */
    static List<JsonNode> relevant(List<JsonNode> cards, String what, Predicate<JsonNode> describedAs) {
        List<String> wanted = words(what);
        if (wanted.isEmpty()) {
            return List.of();
        }
        Set<String> asked = new HashSet<>(wanted);
        boolean forPets = wanted.stream().anyMatch(PET_WORDS::contains);
        List<JsonNode> usable = forPets ? cards : cards.stream().filter(card -> !petProduct(card)).toList();
        List<JsonNode> named = usable.stream().filter(card -> words(Json.text(card, "name") + " "
                + Objects.requireNonNullElse(Json.text(card, "brand.name"), "")).containsAll(wanted)).toList();
        String head = wanted.getLast();
        List<JsonNode> headed = named.stream().filter(card -> {
            List<String> name = words(Json.text(card, "name"));
            return !name.isEmpty() && name.getLast().equals(head);
        }).toList();
        Set<JsonNode> chosen = Collections.newSetFromMap(new IdentityHashMap<>());
        chosen.addAll(headed.isEmpty() ? named : headed);
        Set<JsonNode> withTheWords = Collections.newSetFromMap(new IdentityHashMap<>());
        withTheWords.addAll(named);
        for (JsonNode card : usable) {
            if (withTheWords.contains(card)) {
                continue;                                     // a name the head rule left out (Milk Bread) stays out
            }
            String category = Json.text(card, "category.name");
            if (asked.equals(oneKind(category))
                    || (categoryParts(category).contains(asked) && describedAs.test(card))) {
                chosen.add(card);
            }
        }
        return usable.stream().filter(chosen::contains).toList();
    }

    /** A product of a pet category ("Pet Care"). */
    private static boolean petProduct(JsonNode card) {
        return words(Json.text(card, "category.name")).contains("pet");
    }

    /** A category of one kind as words without "products" ("Dairy Products" → {dairy}); null for several kinds. */
    private static Set<String> oneKind(String category) {
        if (category == null || categoryParts(category).size() != 1) {
            return null;
        }
        Set<String> words = new HashSet<>(words(category));
        words.remove("product");
        return words.isEmpty() ? null : words;
    }

    /** "Biscuits and Cookies" → {biscuit}, {cookie}; "Eggs, Meat and Seafood" → {egg}, {meat}, {seafood}. */
    private static List<Set<String>> categoryParts(String category) {
        if (category == null) {
            return List.of();
        }
        return Arrays.stream(category.split(",|\\band\\b|\\bAnd\\b")).map(part -> (Set<String>) new HashSet<>(
                words(part))).filter(part -> !part.isEmpty()).toList();
    }

    /**
     * One listing per product (name and pack size: Ghee 500 ml and Ghee 1 L are two), in stock: the lowest price,
     * then the nearest store ({@code stores} order); the products by that price (the most expensive first when
     * {@code dearestFirst}).
     */
    static List<JsonNode> ranked(List<JsonNode> cards, List<String> stores, boolean dearestFirst) {
        Comparator<JsonNode> cheaperThenNearer = Comparator.<JsonNode, BigDecimal>comparing(ShoppingCommands::price)
                .thenComparingInt(card -> rank(stores, Json.text(card, "vendor.id")));
        Map<String, JsonNode> best = new LinkedHashMap<>();
        for (JsonNode card : cards) {
            String name = Json.text(card, "name");
            if (name != null && price(card) != null && Json.bool(card, "inStock")) {
                String product = (name + " | " + Objects.requireNonNullElse(Json.text(card, "unitLabel"), ""))
                        .toLowerCase(Locale.ROOT);
                best.merge(product, card, (kept, other) -> cheaperThenNearer.compare(other, kept) < 0 ? other : kept);
            }
        }
        Comparator<JsonNode> byPrice = Comparator.<JsonNode, BigDecimal>comparing(ShoppingCommands::price);
        return best.values().stream().sorted((dearestFirst ? byPrice.reversed() : byPrice)
                .thenComparingInt(card -> rank(stores, Json.text(card, "vendor.id")))
                .thenComparing(card -> Json.text(card, "name"))).toList();
    }

    private static BigDecimal price(JsonNode card) {
        return Json.decimal(card, "price.sellingPrice");
    }

    /** The cards priced between the bounds (both included; null: no bound), as the search filters them. */
    static List<JsonNode> within(List<JsonNode> cards, BigDecimal min, BigDecimal max) {
        return cards.stream().filter(card -> price(card) != null
                && (min == null || price(card).compareTo(min) >= 0)
                && (max == null || price(card).compareTo(max) <= 0)).toList();
    }

    /**
     * " at ₹400.00 or less", " at ₹300.00 or more", " between ₹100.00 and ₹200.00" ({@code at}), or the same without
     * "at" ("costs ₹400.00 or less"); "" without bounds.
     */
    static String priceRange(BigDecimal min, BigDecimal max, boolean at) {
        if (min != null && max != null) {
            return " between " + Money.inr(min) + " and " + Money.inr(max);
        }
        String prefix = at ? " at " : "";
        if (max != null) {
            return prefix + Money.inr(max) + " or less";
        }
        return min != null ? prefix + Money.inr(min) + " or more" : "";
    }

    /**
     * The listing a product answer quotes for one product (its listings in the search's order, one name): of the first
     * listing's pack size, in stock when one is, the cheapest store, then the nearest ({@code stores} order). 2026-11-21
     * (user's choice): each line showed the first store's price, not the cheapest.
     */
    static JsonNode quoted(List<JsonNode> listings, List<String> stores) {
        String unit = Json.text(listings.getFirst(), "unitLabel");
        List<JsonNode> sameSize = listings.stream()
                .filter(card -> Objects.equals(unit, Json.text(card, "unitLabel"))).toList();
        Comparator<JsonNode> cheaperThenNearer = Comparator.<JsonNode, BigDecimal>comparing(ShoppingCommands::price,
                        Comparator.nullsLast(Comparator.<BigDecimal>naturalOrder()))
                .thenComparingInt(card -> rank(stores, Json.text(card, "vendor.id")));
        return sameSize.stream().filter(card -> Json.bool(card, "inStock")).min(cheaperThenNearer)
                .orElseGet(() -> sameSize.stream().min(cheaperThenNearer).orElseThrow());
    }

    /** "Milk (500 ml) – ₹33.00 at Velachery Green Grocers". */
    private static String priceLine(JsonNode listing) {
        String unit = Json.text(listing, "unitLabel");
        return Json.text(listing, "name") + (unit == null ? "" : " (" + unit + ")") + " – " + Money.inr(price(listing))
                + " at " + Json.text(listing, "vendor.name");
    }

    /** "Milk (500 ml) at ₹33.00, from Velachery Green Grocers". */
    private static String priceSentence(JsonNode listing) {
        String unit = Json.text(listing, "unitLabel");
        return Json.text(listing, "name") + (unit == null ? "" : " (" + unit + ")") + " at " + Money.inr(price(listing))
                + ", from " + Json.text(listing, "vendor.name");
    }

    private static String capitalize(String text) {
        return text.isEmpty() ? text : Character.toUpperCase(text.charAt(0)) + text.substring(1);
    }

    /**
     * The customer's words name several things: "tomatoes", "biscuits", "dairy products" (their last word is a plural),
     * so answers say "The cheapest “tomatoes” are …" (2026-11-20: "The cheapest “tomatoes” is Tomato"). "rice", "ghee",
     * "glass" and "hummus" are one thing.
     */
    static boolean plural(String what) {
        String[] parts = what == null ? new String[0] : what.toLowerCase(Locale.ROOT).split("[^a-z]+");
        String last = parts.length == 0 ? "" : parts[parts.length - 1];
        return !last.endsWith("us") && !singular(last).equals(last);
    }

    /** "is" or "are", for the customer's words. */
    private static String isOrAre(String what) {
        return plural(what) ? "are" : "is";
    }

    /** The products an answer listed, with the listings whose prices it gave ("add 2 of the first one"). */
    private void rememberShown(ToolTurn turn, List<JsonNode> listings) {
        ObjectNode shown = choiceNode("shown");
        ArrayNode options = shown.putArray("options");
        ArrayNode ids = shown.putArray("ids");
        for (JsonNode listing : listings) {
            options.add(Json.text(listing, "name"));
            ids.add(Json.text(listing, "id"));
        }
        turn.rememberResult(CHOICE, shown.toString());
    }

    // ---- cart and checkout ----------------------------------------------------------------------------------------

    private String showCart(ToolTurn turn) {
        JsonNode result = run(turn, "getCart", Map.of());
        String failed = failure(result);
        return failed != null ? "I couldn't read your cart: " + failed : describeCart(Json.at(result, "cart"));
    }

    /** "Your cart from Fresh Mart:\n- 2 × Basmati Rice (1 kg) – ₹240.00\nSubtotal: ₹240.00 (2 items)." */
    static String describeCart(JsonNode cart) {
        List<JsonNode> items = Json.list(cart, "items");
        if (items.isEmpty()) {
            return "Your cart is empty.";
        }
        StringBuilder text = new StringBuilder("Your cart" + from(cart) + ":");
        for (JsonNode line : items) {
            text.append("\n- ").append(Json.integer(line, "quantity")).append(" × ").append(Json.text(line, "name"));
            if (Json.text(line, "unitLabel") != null) {
                text.append(" (").append(Json.text(line, "unitLabel")).append(")");
            }
            if (Json.decimal(line, "lineTotal") != null) {
                text.append(" – ").append(Money.inr(Json.decimal(line, "lineTotal")));
            }
            if (Json.text(line, "issue") != null) {
                text.append(" (").append(Json.text(line, "issue").toLowerCase(Locale.ROOT).replace('_', ' '))
                        .append(")");
            }
        }
        int count = itemCount(cart);
        text.append("\nSubtotal: ").append(Money.inr(Json.decimal(cart, "subtotal"))).append(" (").append(count)
                .append(count == 1 ? " item" : " items").append(").");
        String issues = issues(cart, false);
        return issues.isEmpty() ? text.toString() : text + "\n" + issues;
    }

    private String preview(ToolTurn turn) {
        JsonNode result = run(turn, "previewCheckout", Map.of());
        String failed = failure(result);
        if (failed != null) {
            return emptyCart(result) ? "Your cart is empty." : "I couldn't check out your cart: " + failed;
        }
        JsonNode preview = Json.at(result, "preview");
        if (itemCount(preview) == 0) {
            return "Your cart is empty.";
        }
        if (!Json.bool(preview, "canCheckout")) {
            return "Your order can't be placed yet: " + issues(preview, true);
        }
        return total(preview) + " Say “place my order” when you're ready.";
    }

    private String placeOrder(ToolTurn turn) {
        JsonNode result = run(turn, "previewCheckout", Map.of());
        String failed = failure(result);
        if (failed != null) {
            return emptyCart(result) ? "Your cart is empty, so there is nothing to order yet."
                    : "I couldn't check out your cart: " + failed;
        }
        JsonNode preview = Json.at(result, "preview");
        if (itemCount(preview) == 0) {
            return "Your cart is empty, so there is nothing to order yet.";
        }
        if (!Json.bool(preview, "canCheckout")) {
            return "Your order can't be placed yet: " + issues(preview, true);
        }
        JsonNode proposal = run(turn, "placeOrder",
                Map.of("rationale", "The customer asked to place the order for the current cart."));
        String refused = failure(proposal);
        if (refused != null) {
            return "I couldn't prepare your order: " + refused;
        }
        return "I've prepared your order. " + total(preview) + " Nothing is ordered until you tap Confirm order on the "
                + "card below.";
    }

    /** "Your total is ₹265.00: ₹240.00 for 2 items from Fresh Mart + ₹25.00 delivery to Home." */
    static String total(JsonNode preview) {
        int count = itemCount(preview);
        BigDecimal fee = Json.decimal(preview, "deliveryFee");
        String place = Json.text(preview, "address.label");
        return "Your total is " + Money.inr(Json.decimal(preview, "total")) + ": "
                + Money.inr(Json.decimal(preview, "subtotal")) + " for " + count + (count == 1 ? " item" : " items")
                + from(preview)
                + (fee == null ? "" : fee.signum() == 0 ? " with free delivery" : " + " + Money.inr(fee) + " delivery")
                + (place == null ? "" : " to " + place) + ".";
    }

    private String clearCart(ToolTurn turn) {
        JsonNode cart = readCart(turn);
        if (cart == null) {
            return "I couldn't read your cart right now. Please try again in a moment.";
        }
        List<JsonNode> lines = Json.list(cart, "lines");
        if (lines.isEmpty()) {
            return "Your cart is already empty.";
        }
        if (lines.size() > MAX_CLEARED_LINES) {
            return "Your cart has " + lines.size() + " products: you can empty it on the cart page. [My cart](/cart)";
        }
        int removed = 0;
        for (JsonNode line : lines) {
            String failed = failure(run(turn, "updateCartItem",
                    Map.of("itemId", Json.text(line, "itemId"), "quantity", 0)));
            if (failed != null) {
                return (removed == 0 ? "" : "I removed " + removed + (removed == 1 ? " product" : " products")
                        + ", then: ") + "That didn't work: " + failed;
            }
            removed++;
        }
        return "Emptied your cart (removed " + removed + (removed == 1 ? " product" : " products") + ").";
    }

    /**
     * "remove the tomatoes" ({@code quantity} 0), "remove 1 milk" (−1) or "set rice to 3" ({@code set}): the cart line
     * whose name has the words, asking which one when several do.
     */
    private String changeLine(ToolTurn turn, String what, int quantity, boolean set) {
        if (set && quantity > MAX_QUANTITY) {
            return "I can set at most " + MAX_QUANTITY + " of one product.";
        }
        JsonNode cart = readCart(turn);
        if (cart == null) {
            return "I couldn't read your cart right now. Please try again in a moment.";
        }
        List<JsonNode> lines = Json.list(cart, "lines");
        if (lines.isEmpty()) {
            return "Your cart is empty.";
        }
        List<String> wanted = words(what);
        List<JsonNode> exact = lines.stream()
                .filter(line -> new HashSet<>(words(Json.text(line, "name"))).equals(new HashSet<>(wanted))).toList();
        List<JsonNode> matching = exact.isEmpty() ? lines.stream()
                .filter(line -> words(Json.text(line, "name")).containsAll(wanted)).toList() : exact;
        if (matching.isEmpty()) {
            return (plural(what) ? "There are no " : "There's no ") + what + " in your cart. It has " + lines.stream()
                    .map(line -> Json.text(line, "name")).collect(Collectors.joining(", ")) + ".";
        }
        if (matching.size() > 1) {
            ObjectNode choice = choiceNode("line");
            choice.put("set", set);
            choice.put("quantity", quantity);
            ArrayNode options = choice.putArray("options");
            matching.stream().limit(MAX_OPTIONS).forEach(line -> options.addObject()
                    .put("itemId", Json.text(line, "itemId")).put("name", Json.text(line, "name")));
            turn.rememberResult(CHOICE, choice.toString());
            return "Which one?" + numbered(matching.stream().limit(MAX_OPTIONS)
                    .map(line -> Json.text(line, "name")).toList());
        }
        JsonNode line = matching.getFirst();
        return applyLine(turn, Json.text(line, "itemId"), Json.text(line, "name"),
                Objects.requireNonNullElse(Json.integer(line, "quantity"), 0), quantity, set);
    }

    private String applyLine(ToolTurn turn, String itemId, String name, int current, int quantity, boolean set) {
        int target = set ? quantity : quantity < 0 ? Math.max(0, current + quantity) : 0;
        JsonNode result = run(turn, "updateCartItem", Map.of("itemId", itemId, "quantity", target));
        String failed = failure(result);
        if (failed != null) {
            return "That didn't work: " + failed;
        }
        String done = target == 0 ? "Removed " + name + " from your cart."
                : set ? "Set " + name + " to " + target + "."
                : "Your cart now has " + target + " × " + name + ".";
        return done + " " + summary(Json.at(result, "cart"));
    }

    // ---- adding ---------------------------------------------------------------------------------------------------

    /** What adding one product led to. */
    private sealed interface Outcome {
    }

    private record Added(String text, JsonNode cart, String storeId) implements Outcome {
    }

    private record Asked(String question) implements Outcome {
    }

    private record Said(String text) implements Outcome {
    }

    /** Each product in turn; a question about one stops there (the others are named to ask again). */
    private String add(ToolTurn turn, List<Item> items, JsonNode choice, List<String> notes) {
        JsonNode cart = readCart(turn);
        String cartStore = cart == null || Json.list(cart, "lines").isEmpty() ? null : Json.text(cart, "store.id");
        String cartStoreName = cartStore == null ? null : Json.text(cart, "store.name");
        ShoppingTools.StoreScope scope = shoppingTools.storeScope(turn.http());
        if (scope.noneDeliver()) {
            return "No grocery store delivers to " + scope.place() + " yet, so I can't add products there.";
        }
        List<String> said = new ArrayList<>();
        List<String> later = new ArrayList<>();
        JsonNode lastCart = null;
        String question = null;
        for (Item item : items) {
            if (question != null) {
                later.add(item.what() != null ? item.what() : "the other product");
                continue;
            }
            Item named = item;
            JsonNode priced = null;
            if (item.reference() != null) {
                String name = referenced(item.reference(), choice, notes);
                if (name == null) {
                    if (items.size() == 1) {
                        return null;                           // the model knows the conversation better
                    }
                    said.add("I'm not sure which product you mean by “" + describe(item) + "”.");
                    continue;
                }
                named = item.named(name);
                priced = shownListing(turn, choice, notes, item.reference());
            }
            Outcome outcome = addOne(turn, named, cart, cartStore, cartStoreName, scope, priced);
            switch (outcome) {
                case Added added -> {
                    said.add(added.text());
                    lastCart = added.cart();
                    if (cartStore == null) {
                        cartStore = added.storeId();
                        cartStoreName = Json.text(added.cart(), "store.name");
                    }
                }
                case Asked asked -> question = asked.question();
                case Said text -> said.add(text.text());
            }
        }
        StringBuilder answer = new StringBuilder(String.join("\n", said));
        if (lastCart != null) {
            answer.append(answer.isEmpty() ? "" : "\n").append(summary(lastCart));
        }
        if (question != null) {
            answer.append(answer.isEmpty() ? "" : "\n").append(question);
            if (!later.isEmpty()) {
                answer.append("\nThen ask me again for ").append(String.join(", ", later)).append(".");
            }
        }
        return answer.toString();
    }

    /** "2 × rice", "a kg of tomato" in the customer's words, for messages. */
    private static String describe(Item item) {
        return item.what() != null ? item.what() : "that";
    }

    /**
     * @param priced the listing a price answer named for this product (its price was given), or null; used unless the
     *               cart holds another store or it is out of stock now
     */
    private Outcome addOne(ToolTurn turn, Item item, JsonNode cart, String cartStore, String cartStoreName,
                           ShoppingTools.StoreScope scope, JsonNode priced) {
        // A product already in the cart: "rice 1 add" adds to the Daawat Basmati Rice there.
        JsonNode line = cartLine(cart, item.what());
        if (line != null) {
            Integer packs = packs(item, Json.text(line, "unitLabel"));
            if (packs != null) {
                return addListing(turn, listing(line, cart), packs, false);
            }
        }
        if (priced != null && Json.bool(priced, "inStock")
                && (cartStore == null || cartStore.equals(Json.text(priced, "vendor.id")))) {
            return addPacks(turn, item, priced, cartStore == null);
        }
        List<String> stores = cartStore != null ? List.of(cartStore) : scope.vendorIds();
        List<JsonNode> cards = search(turn, item.what(), stores);
        if (cards == null) {
            return new Said("The product search isn't available right now, so I couldn't add " + item.what() + ".");
        }
        Pick pick = pickName(cards, item.what());
        boolean matched = pick.name() != null || (!pick.options().isEmpty() && !pick.exactMissing());
        if (!matched && cartStore != null) {
            // Nothing with these words in the cart's store (a cart holds one store): is it at another one?
            List<JsonNode> elsewhere = search(turn, item.what(), scope.vendorIds());
            Pick other = elsewhere == null ? Pick.NONE : pickName(elsewhere, item.what());
            if (other.name() != null || (!other.options().isEmpty() && !other.exactMissing())) {
                String name = other.name() != null ? other.name() : other.options().getFirst();
                JsonNode listing = best(elsewhere, name, scope.vendorIds());
                return new Said("Your cart has products from " + cartStoreName + ", which doesn't have " + item.what()
                        + (listing == null ? "" : " (" + Json.text(listing, "vendor.name") + " has " + name + ")")
                        + ". A cart holds one store: to order from another store, empty your cart first (say “clear "
                        + "my cart”).");
            }
        }
        if (pick.name() == null) {
            if (pick.options().isEmpty()) {
                return new Said("I couldn't find " + item.what() + (scope.place() == null ? ""
                        : " in the stores that deliver to " + scope.place()) + ".");
            }
            rememberProductChoice(turn, item, pick.options());
            if (pick.options().size() == 1) {
                return new Asked("Do you mean " + options(cards, pick.options(), stores).substring(4) + "? Say “yes” "
                        + "to add it.");
            }
            return new Asked((pick.exactMissing() ? "I couldn't find exactly “" + item.what() + "”. Did you mean one "
                    + "of these?" : "Which one would you like?") + options(cards, pick.options(), stores)
                    + "\nSay “the first one”, “the second one”…");
        }
        JsonNode listing = best(cards, pick.name(), stores);
        if (listing == null) {
            return new Said("I couldn't find " + pick.name() + ".");
        }
        if (!Json.bool(listing, "inStock")) {
            return new Said(pick.name() + " is out of stock at " + Json.text(listing, "vendor.name") + " right now.");
        }
        return addPacks(turn, item, listing, cartStore == null);
    }

    /** The listing in as many packs as asked; a weight that is not whole packs asks how many. */
    private Outcome addPacks(ToolTurn turn, Item item, JsonNode listing, boolean newCart) {
        Integer packs = packs(item, Json.text(listing, "unitLabel"));
        if (packs == null) {
            ObjectNode choice = choiceNode("packs");
            choice.set("listing", Results.pick(listing, "id", "name", "unitLabel", "price", "vendor", "inStock"));
            turn.rememberResult(CHOICE, choice.toString());
            return new Asked(packQuestion(listing));
        }
        return addListing(turn, listing, packs, newCart);
    }

    /**
     * The one product line of the cart whose name has the words (exactly these words first), or null: none, several,
     * or a restaurant dish.
     */
    private static JsonNode cartLine(JsonNode cart, String what) {
        List<String> wanted = words(what);
        if (cart == null || wanted.isEmpty()) {
            return null;
        }
        List<JsonNode> products = Json.list(cart, "lines").stream()
                .filter(line -> "PRODUCT".equals(Json.text(line, "type"))).toList();
        List<JsonNode> exact = products.stream()
                .filter(line -> new HashSet<>(words(Json.text(line, "name"))).equals(new HashSet<>(wanted))).toList();
        List<JsonNode> matching = exact.isEmpty() ? products.stream()
                .filter(line -> words(Json.text(line, "name")).containsAll(wanted)).toList() : exact;
        return matching.size() == 1 ? matching.getFirst() : null;
    }

    /** A cart line in the shape of a search card (a grocery line's itemId is its product id). */
    private ObjectNode listing(JsonNode line, JsonNode cart) {
        ObjectNode listing = jsonMapper.createObjectNode();
        listing.put("id", Json.text(line, "itemId"));
        listing.put("name", Json.text(line, "name"));
        listing.put("unitLabel", Json.text(line, "unitLabel"));
        if (Json.decimal(line, "unitPrice") != null) {
            listing.putObject("price").put("sellingPrice", Json.decimal(line, "unitPrice"));
        }
        listing.putObject("vendor").put("id", Json.text(cart, "store.id")).put("name", Json.text(cart, "store.name"));
        listing.put("inStock", true);
        return listing;
    }

    /** addToCart through the guard; the line for the answer. */
    private Outcome addListing(ToolTurn turn, JsonNode listing, int packs, boolean newCart) {
        String name = Json.text(listing, "name");
        if (packs > MAX_QUANTITY) {
            return new Said("I can add at most " + MAX_QUANTITY + " of one product at a time (you asked for " + packs
                    + " × " + name + ").");
        }
        JsonNode result = run(turn, "addToCart", Map.of("productId", Json.text(listing, "id"), "quantity", packs));
        String failed = failure(result);
        if (failed != null) {
            return new Said("I couldn't add " + name + ": " + failed);
        }
        String unit = Json.text(listing, "unitLabel");
        BigDecimal price = Json.decimal(listing, "price.sellingPrice");
        String details = Stream.of(unit, price == null ? null : Money.inr(price) + " each").filter(Objects::nonNull)
                .collect(Collectors.joining(", "));
        return new Added("Added " + packs + " × " + name + (details.isEmpty() ? "" : " (" + details + ")")
                + (newCart ? " from " + Json.text(listing, "vendor.name") : "") + ".", Json.at(result, "cart"),
                Json.text(listing, "vendor.id"));
    }

    /** Which product name a search means: one name, or the options to ask about. */
    record Pick(String name, List<String> options, boolean exactMissing) {
        static final Pick NONE = new Pick(null, List.of(), false);
    }

    /**
     * The product the words mean: the name with exactly these words; else the only name containing them all whose
     * other words are its brand ("basmati rice" → Daawat Basmati Rice). The only name with other words is asked about
     * ("tomatoes" → "Do you mean Tomato Ketchup?"), several names → which one; none → what the search found ("did you
     * mean").
     */
    static Pick pickName(List<JsonNode> cards, String what) {
        List<String> wanted = words(what);
        if (wanted.isEmpty() || cards.isEmpty()) {
            return Pick.NONE;
        }
        for (JsonNode card : cards) {
            if (new HashSet<>(words(Json.text(card, "name"))).equals(new HashSet<>(wanted))) {
                return new Pick(Json.text(card, "name"), List.of(), false);
            }
        }
        List<JsonNode> matchingCards = cards.stream()
                .filter(card -> words(Json.text(card, "name") + " " + Objects.requireNonNullElse(
                        Json.text(card, "brand.name"), "")).containsAll(wanted)).toList();
        // "milk" is Aavin Full Cream Milk, not Milk Bread or Milk Chocolate: names ending in the last word asked for
        String head = wanted.getLast();
        List<JsonNode> headCards = matchingCards.stream().filter(card -> {
            List<String> name = words(Json.text(card, "name"));
            return !name.isEmpty() && name.getLast().equals(head);
        }).toList();
        if (!headCards.isEmpty()) {
            matchingCards = headCards;
        }
        List<String> matching = matchingCards.stream().map(card -> Json.text(card, "name")).distinct().toList();
        if (matching.size() == 1) {
            JsonNode card = matchingCards.getFirst();
            Set<String> other = new HashSet<>(words(Json.text(card, "name")));
            other.removeAll(wanted);
            other.removeAll(words(Json.text(card, "brand.name")));
            return other.isEmpty() ? new Pick(matching.getFirst(), List.of(), false)
                    : new Pick(null, matching, false);
        }
        if (matching.size() > 1) {
            return new Pick(null, matching.stream().limit(MAX_OPTIONS).toList(), false);
        }
        return new Pick(null, cards.stream().map(card -> Json.text(card, "name")).distinct().limit(MAX_OPTIONS)
                .toList(), true);
    }

    /** The listing of {@code name} to add: in stock first, then the nearest store ({@code stores} order). */
    private static JsonNode best(List<JsonNode> cards, String name, List<String> stores) {
        return cards.stream().filter(card -> name.equalsIgnoreCase(Json.text(card, "name")))
                .min(Comparator.<JsonNode, Boolean>comparing(card -> !Json.bool(card, "inStock"))
                        .thenComparingInt(card -> rank(stores, Json.text(card, "vendor.id"))))
                .orElse(null);
    }

    private static int rank(List<String> stores, String storeId) {
        int index = stores == null ? -1 : stores.indexOf(storeId);
        return index < 0 ? Integer.MAX_VALUE : index;
    }

    /** "\n1. Aavin Full Cream Milk (1 L, ₹68.00)\n2. …": each name with its nearest listing's size and price. */
    private static String options(List<JsonNode> cards, List<String> names, List<String> stores) {
        List<String> lines = new ArrayList<>();
        for (String name : names) {
            JsonNode listing = best(cards, name, stores);
            String unit = listing == null ? null : Json.text(listing, "unitLabel");
            BigDecimal price = listing == null ? null : Json.decimal(listing, "price.sellingPrice");
            String details = Stream.of(unit, price == null ? null : Money.inr(price)).filter(Objects::nonNull)
                    .collect(Collectors.joining(", "));
            lines.add(name + (details.isEmpty() ? "" : " (" + details + ")"));
        }
        return numbered(lines);
    }

    private static String numbered(List<String> lines) {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < lines.size(); i++) {
            text.append("\n").append(i + 1).append(". ").append(lines.get(i));
        }
        return text.toString();
    }

    // ---- packs ----------------------------------------------------------------------------------------------------

    private static final Pattern LABEL = Pattern.compile("^(\\d+(?:\\.\\d+)?)\\s*(kg|g|l|ml|pcs)$",
            Pattern.CASE_INSENSITIVE);

    /**
     * How many packs: "a kg of tomatoes" with 1 kg packs → 1, "1.5 kg" with 500 g → 3; "2 rice" → 2. Null when it is
     * not clear ("2 kg atta" with 5 kg packs, "6 eggs" with packs of 6): the answer asks.
     */
    static Integer packs(Item item, String unitLabel) {
        Matcher label = unitLabel == null ? null : LABEL.matcher(unitLabel.strip());
        boolean known = label != null && label.matches();
        if (item.amount() != null) {
            if (!known) {
                return null;
            }
            String unit = label.group(2).toLowerCase(Locale.ROOT);
            boolean volume = unit.equals("l") || unit.equals("ml");
            if (unit.equals("pcs") || volume != item.volume()) {
                return null;
            }
            BigDecimal size = new BigDecimal(label.group(1))
                    .multiply(unit.equals("kg") || unit.equals("l") ? BigDecimal.valueOf(1000) : BigDecimal.ONE);
            BigDecimal[] division = item.amount().divideAndRemainder(size);
            return division[1].signum() == 0 && division[0].signum() > 0 ? division[0].intValueExact() : null;
        }
        if (known && label.group(2).equalsIgnoreCase("pcs") && new BigDecimal(label.group(1)).intValue() > 1
                && item.counted() && !item.packUnit() && item.count() > 1) {
            return null;                                      // "6 eggs" with packs of 6: 6 packs or one?
        }
        return item.count();
    }

    /** "Wheat Atta comes in 5 kg packs (₹260.00 each). How many packs would you like?" */
    private static String packQuestion(JsonNode listing) {
        String unit = Objects.requireNonNullElse(Json.text(listing, "unitLabel"), "");
        Matcher label = LABEL.matcher(unit);
        String size = label.matches() && label.group(2).equalsIgnoreCase("pcs") ? "packs of " + label.group(1)
                : unit + " packs";
        BigDecimal price = Json.decimal(listing, "price.sellingPrice");
        return Json.text(listing, "name") + " comes in " + size + (price == null ? "" : " (" + Money.inr(price)
                + " each)") + ". How many packs would you like?";
    }

    // ---- replies and references -----------------------------------------------------------------------------------

    private String answerReply(ToolTurn turn, Reply reply, JsonNode choice, List<String> notes) {
        String ask = Json.text(choice, "ask");
        if ("packs".equals(ask)) {
            int packs = reply.number() != null ? reply.number() : reply.yes() ? 1 : -1;
            JsonNode chosen = Json.at(choice, "listing");
            if (packs < 1 || chosen == null || Json.text(chosen, "id") == null) {
                return null;
            }
            JsonNode cart = readCart(turn);
            boolean newCart = cart == null || Json.list(cart, "lines").isEmpty();
            return switch (addListing(turn, chosen, packs, newCart)) {
                case Added added -> added.text() + "\n" + summary(added.cart());
                case Said said -> said.text();
                case Asked asked -> asked.question();
            };
        }
        List<JsonNode> options = Json.list(choice, "options");
        int index = index(reply, options.size());
        if (index < 0) {
            return null;
        }
        if ("line".equals(ask)) {
            JsonNode option = options.get(index);
            JsonNode cart = readCart(turn);
            JsonNode line = cart == null ? null : Json.list(cart, "lines").stream()
                    .filter(each -> Objects.equals(Json.text(each, "itemId"), Json.text(option, "itemId")))
                    .findFirst().orElse(null);
            if (line == null) {
                return "That product is no longer in your cart.";
            }
            return applyLine(turn, Json.text(line, "itemId"), Json.text(line, "name"),
                    Objects.requireNonNullElse(Json.integer(line, "quantity"), 0),
                    Objects.requireNonNullElse(Json.integer(choice, "quantity"), 0), Json.bool(choice, "set"));
        }
        if ("product".equals(ask)) {
            Item item = new Item(Objects.requireNonNullElse(Json.integer(choice, "count"), 1),
                    Json.bool(choice, "counted"), Json.bool(choice, "packUnit"), Json.decimal(choice, "amount"),
                    Json.bool(choice, "volume"), options.get(index).asString(), null);
            return add(turn, List.of(item), null, notes);
        }
        return null;
    }

    /** The option a reply picks (0-based), or −1. "yes" picks the only option. */
    private static int index(Reply reply, int size) {
        if (reply.yes()) {
            return size == 1 ? 0 : -1;
        }
        Integer number = reply.ordinal() != null ? reply.ordinal() : reply.number();
        if (number == null) {
            return -1;
        }
        int index = number == LAST ? size - 1 : number - 1;
        return index >= 0 && index < size ? index : -1;
    }

    private void rememberProductChoice(ToolTurn turn, Item item, List<String> names) {
        ObjectNode choice = choiceNode("product");
        choice.put("count", item.count());
        choice.put("counted", item.counted());
        choice.put("packUnit", item.packUnit());
        if (item.amount() != null) {
            choice.put("amount", item.amount());
            choice.put("volume", item.volume());
        }
        ArrayNode options = choice.putArray("options");
        names.forEach(options::add);
        turn.rememberResult(CHOICE, choice.toString());
    }

    private ObjectNode choiceNode(String ask) {
        ObjectNode choice = jsonMapper.createObjectNode();
        choice.put("ask", ask);
        return choice;
    }

    private static final JsonMapper NOTES = JsonMapper.builder().build();

    /** This layer's question or list in an answer's note ("quickChoice → {…}"), or null. */
    static JsonNode choice(String note) {
        String json = toolLine(note, CHOICE);
        if (json == null) {
            return null;
        }
        try {
            return NOTES.readTree(json);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * The products this layer's answers listed last ({@code "ask":"shown"}: their names, and for price answers the
     * listings whose prices were given): the previous answer's, else the newest note's, unless a later note asked
     * something else or holds the model's own search. Null when there is none.
     */
    static JsonNode shown(JsonNode choice, List<String> notes) {
        if (choice != null) {
            return "shown".equals(Json.text(choice, "ask")) ? choice : null;
        }
        for (int i = notes.size() - 1; i >= 0; i--) {
            JsonNode listed = choice(notes.get(i));
            if (listed != null) {
                return "shown".equals(Json.text(listed, "ask")) ? listed : null;
            }
            if (toolLine(notes.get(i), "searchProducts") != null) {
                return null;                                   // the model searched since: its products
            }
        }
        return null;
    }

    /**
     * The listing a price answer gave the price of for the product {@code reference} means (the same list as
     * {@link #referenced}; by position: Ghee 500 ml and Ghee 1 L share a name), read again (stock and price now), or
     * null.
     */
    private JsonNode shownListing(ToolTurn turn, JsonNode choice, List<String> notes, int reference) {
        JsonNode listed = shown(choice, notes);
        List<JsonNode> ids = Json.list(listed, "ids");
        int index = position(reference, Json.list(listed, "options").size());
        if (index < 0 || index >= ids.size()) {
            return null;
        }
        return api.get(turn.http(), "/api/catalog/products/" + ids.get(index).asString()) instanceof Fetch.Ok ok
                ? ok.body() : null;
    }

    /** The index {@code reference} means in a list of {@code size}, or −1 ("that" needs a single product). */
    private static int position(int reference, int size) {
        if (reference == THAT) {
            return size == 1 ? 0 : -1;
        }
        int index = reference == LAST ? size - 1 : reference;
        return index >= 0 && index < size ? index : -1;
    }

    /**
     * The product "that" or "the second one" means: from this layer's last question, else from the products shown
     * last (the list an answer of this layer showed, or the newest searchProducts result of the model). "That" needs a
     * single product.
     */
    static String referenced(int reference, JsonNode choice, List<String> notes) {
        JsonNode listed = choice != null && "product".equals(Json.text(choice, "ask")) ? choice : shown(choice, notes);
        List<String> names = listed != null
                ? Json.list(listed, "options").stream().map(JsonNode::asString).toList()
                : lastSearchNames(notes);
        int index = position(reference, names.size());
        return index < 0 ? null : names.get(index);
    }

    private static final Pattern PRODUCT_NAME = Pattern.compile(
            "\\{\"id\":\"[0-9a-fA-F-]{36}\",\"name\":\"((?:[^\"\\\\]|\\\\.)*)\"");

    /** The distinct product names of the newest searchProducts result in the notes, in the order shown. */
    static List<String> lastSearchNames(List<String> notes) {
        for (int i = notes.size() - 1; i >= 0; i--) {
            String json = toolLine(notes.get(i), "searchProducts");
            if (json == null) {
                continue;
            }
            Set<String> names = new LinkedHashSet<>();
            Matcher matcher = PRODUCT_NAME.matcher(json);
            while (matcher.find()) {
                names.add(matcher.group(1).replace("\\\"", "\""));
            }
            if (!names.isEmpty()) {
                return List.copyOf(names);
            }
        }
        return List.of();
    }

    /** The newest "tool → json" line of {@code tool} in a note (each remembered result is one line). */
    private static String toolLine(String note, String tool) {
        if (note == null) {
            return null;
        }
        String found = null;
        for (String line : note.split("\n")) {
            if (line.startsWith(tool + " → ")) {
                found = line.substring(tool.length() + 3);
            }
        }
        return found;
    }

    // ---- helpers --------------------------------------------------------------------------------------------------

    /** One tool call through the guard (audited, streamed as a {@code tool} event, remembered); its JSON result. */
    private JsonNode run(ToolTurn turn, String tool, Map<String, Object> args) {
        return jsonMapper.readTree(guard.invoke(turn, tool, jsonMapper.writeValueAsString(args)));
    }

    /** The refusal or error message of a tool call, or null when it worked. */
    static String failure(JsonNode result) {
        String status = Json.text(result, "status");
        return "ERROR".equals(status) || "BLOCKED".equals(status)
                ? Objects.requireNonNullElse(Json.text(result, "message"), "it did not work") : null;
    }

    private static boolean emptyCart(JsonNode result) {
        String code = Objects.requireNonNullElse(Json.text(result, "code"), "");
        return code.contains("EMPTY");
    }

    /** The customer's cart as cart-service returns it (lines with item ids), or null when it cannot be read. */
    private JsonNode readCart(ToolTurn turn) {
        return api.get(turn.http(), "/api/cart") instanceof Fetch.Ok ok ? ok.body() : null;
    }

    /** Products for {@code query} in {@code stores} (null = every store), or null when the search failed. */
    private List<JsonNode> search(ToolTurn turn, String query, List<String> stores) {
        return search(turn, query, stores, SEARCH_SIZE);
    }

    private List<JsonNode> search(ToolTurn turn, String query, List<String> stores, int size) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("q", query);
        params.put("size", size);
        params.put("vendorId", stores);
        return api.get(turn.http(), "/api/search/products", params) instanceof Fetch.Ok ok
                ? Json.list(ok.body(), "content") : null;
    }

    /** "Your cart: 3 items from Fresh Mart, subtotal ₹280.00." */
    static String summary(JsonNode cart) {
        int count = itemCount(cart);
        if (cart == null || count == 0) {
            return "Your cart is now empty.";
        }
        return "Your cart: " + count + (count == 1 ? " item" : " items") + from(cart) + ", subtotal "
                + Money.inr(Json.decimal(cart, "subtotal")) + ".";
    }

    private static String from(JsonNode cart) {
        String store = Json.text(cart, "store.name");
        return store == null ? "" : " from " + store;
    }

    private static int itemCount(JsonNode cart) {
        BigDecimal count = Json.decimal(cart, "itemCount");
        return count == null ? Json.list(cart, "items").size() : count.intValue();
    }

    /** The issues' messages; only the blocking ones when {@code blockingOnly}. */
    private static String issues(JsonNode cart, boolean blockingOnly) {
        return Json.list(cart, "issues").stream()
                .filter(issue -> !blockingOnly || Json.bool(issue, "blocking"))
                .map(issue -> Objects.requireNonNullElse(Json.text(issue, "message"), Json.text(issue, "code")))
                .filter(Objects::nonNull).collect(Collectors.joining(" "));
    }

    private static final Set<String> STOP_WORDS = Set.of("of", "the", "a", "an", "some", "my", "me", "for", "to",
            "in", "pack", "packet", "packs", "packets");

    /** The words of a product name or request, lower case and singular ("Tomatoes" → "tomato"). */
    static List<String> words(String text) {
        if (text == null) {
            return List.of();
        }
        return Arrays.stream(text.toLowerCase(Locale.ROOT).split("[^a-z0-9]+"))
                .filter(word -> !word.isBlank() && !STOP_WORDS.contains(word))
                .map(ShoppingCommands::singular).toList();
    }

    static String singular(String word) {
        if (word.length() > 4 && word.endsWith("ies")) {
            return word.substring(0, word.length() - 3) + "y";
        }
        if (word.length() > 4 && (word.endsWith("oes") || word.endsWith("ses") || word.endsWith("xes")
                || word.endsWith("ches") || word.endsWith("shes"))) {
            return word.substring(0, word.length() - 2);
        }
        if (word.length() > 3 && word.endsWith("s") && !word.endsWith("ss")) {
            return word.substring(0, word.length() - 1);
        }
        return word;
    }
}
