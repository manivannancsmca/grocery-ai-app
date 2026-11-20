package com.grocery.ai.model;

import com.grocery.ai.insights.InsightRules;
import com.grocery.ai.insights.InsightsService;
import com.grocery.ai.support.Json;
import com.grocery.ai.support.Money;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import reactor.core.publisher.Flux;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * The {@code scripted} provider (Phase 11 contract §7, Phase 12 §6): a deterministic stand-in for an LLM, implemented
 * as a real Spring AI {@link ChatModel} (call and stream). It reads the latest user message (case-insensitive, first
 * matching rule wins), answers with tool-call messages exactly as a real model would — so ToolGuard, the tool calling
 * loop and the APIs run unchanged — and, once the tool results are back, answers from a template that summarises them.
 *
 * <p>It knows which agent it serves from the tools in the request (like a model would): admin-bi
 * ({@code getOrderStats}), vendor ({@code listInventory}), restaurant ({@code getOrderQueue}), delivery
 * ({@code getActiveDelivery}), payment ({@code listMyPayments}), support ({@code fileComplaint}), else shopping. Local
 * and docker profiles only (see ModelProviderConfig).
 */
public class ScriptedChatModel implements ChatModel {

    public static final String MODEL = "scripted";
    private static final ZoneId INDIA = ZoneId.of("Asia/Kolkata");
    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;
    private static final int FLAGS = Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE | Pattern.DOTALL;

    // shopping
    private static final Pattern ADD = Pattern.compile("^add\\s+(\\d+)\\s+(.+)$", FLAGS);
    private static final Pattern SEARCH = Pattern.compile("^(?:search|find)\\s+(.+)$", FLAGS);
    /** "how many products below 50 rupees", "find paneer under ₹200", "items less than rs. 30". */
    private static final Pattern PRICED = Pattern.compile(
            "^(?:how\\s+many\\s+|show\\s+(?:me\\s+)?|list\\s+|find\\s+|search\\s+)?(.+?)"
                    + "(?:\\s+(?:are|is))?(?:\\s+there)?(?:\\s+(?:priced|costing|that\\s+cost|which\\s+cost))?"
                    + "\\s+(?:below|under|less\\s+than|cheaper\\s+than|up\\s*to|within|at\\s+most)\\s+"
                    + "(?:₹|rs\\.?|inr)?\\s*(\\d{1,7}(?:\\.\\d{1,2})?)\\s*(?:rupees?|rs\\.?|inr)?$", FLAGS);
    /** Words that mean "any product" in a price question: the search then has no query. */
    private static final Pattern ANY_PRODUCT = Pattern.compile(
            "^(?:(?:all|the|any)\\s+)?(?:products?|items?|groceries|things|stuff|anything|everything)$", FLAGS);
    private static final Pattern CART = Pattern.compile("^(?:cart|show my cart)$", FLAGS);
    private static final Pattern CHECKOUT = Pattern.compile("^(?:checkout|place my order)$", FLAGS);
    private static final Pattern CANCEL_ORDER = Pattern.compile("^cancel\\s+order\\s+(\\S+)$", FLAGS);
    private static final Pattern STATUS_OF = Pattern.compile("^status\\s+of\\s+(\\S+)$", FLAGS);
    private static final Pattern RETURN = Pattern.compile(
            "^return\\s+(\\d+)\\s+(.+?)\\s+from\\s+(\\S+)\\s+because\\s+(.+)$", FLAGS);
    /** "find organic apples in the fruits category". */
    private static final Pattern IN_CATEGORY = Pattern.compile(
            "^(?:search|find|show)\\s+(?:me\\s+)?(.+?)\\s+in\\s+(?:the\\s+)?(.+?)\\s+category$", FLAGS);
    /** "where is my latest order", "my last order status". */
    private static final Pattern LATEST_ORDER = Pattern.compile(
            "^(?:where\\s+is\\s+my\\s+(?:latest|last)\\s+order|(?:my\\s+)?(?:latest|last)\\s+order(?:\\s+status)?)$",
            FLAGS);
    // admin-bi
    private static final Pattern STATS = Pattern.compile("^(?:stats|sales today)$", FLAGS);
    private static final Pattern STUCK = Pattern.compile("^stuck orders$", FLAGS);
    private static final Pattern REFUND = Pattern.compile("^refund\\s+(\\S+)\\s+for\\s+(\\S+)\\s+because\\s+(.+)$",
            FLAGS);
    private static final Pattern ADMIN_CANCEL = Pattern.compile("^cancel\\s+(?:order\\s+)?(\\S+)\\s+because\\s+(.+)$",
            FLAGS);
    private static final Pattern SUSPEND = Pattern.compile(
            "^suspend\\s+(vendor|restaurant)\\s+(\\S+)\\s+because\\s+(.+)$", FLAGS);
    /** "how many vendors have been approved by the admin", "how many restaurants joined", "how many customers". */
    private static final Pattern HOW_MANY_PARTNERS = Pattern.compile(
            "^how\\s+many\\s+(vendors?|restaurants?|delivery\\s+partners?|partners?|customers?)\\b.*$", FLAGS);
    /** "show pending vendor approvals", "list suspended restaurants", "pending delivery partners". */
    private static final Pattern LIST_PARTNERS = Pattern.compile(
            "^(?:(?:show|list)(?:\\s+me)?\\s+)?(?:the\\s+|all\\s+)?(pending|approved|suspended|rejected|draft)?\\s*"
                    + "(vendor|restaurant|delivery\\s+partner|partner)s?(?:\\s+approvals?)?$", FLAGS);
    // vendor and restaurant
    private static final Pattern SALES = Pattern.compile("^sales(?:\\s+report)?$", FLAGS);
    private static final Pattern LOW_STOCK = Pattern.compile(
            "^(?:low\\s+stock|(?:which|what)\\s+(?:products?|items?)\\s+(?:are\\s+)?running\\s+low"
                    + "(?:\\s+on\\s+stock)?)$", FLAGS);
    /** "how many orders did I get today", "how many orders have I received today". */
    private static final Pattern ORDERS_TODAY = Pattern.compile(
            "^how\\s+many\\s+orders\\s+(?:did\\s+i\\s+(?:get|receive)|have\\s+i\\s+(?:got|received))\\s+today$",
            FLAGS);
    private static final Pattern SET_STOCK = Pattern.compile("^set\\s+stock\\s+(?:of\\s+)?(.+?)\\s+to\\s+(\\d+)$",
            FLAGS);
    private static final Pattern PRICE = Pattern.compile("^price\\s+(?:of\\s+)?(.+?)\\s+(?:to\\s+)?₹?(\\d+(?:\\.\\d+)?)$",
            FLAGS);
    private static final Pattern ORDERS = Pattern.compile("^(?:orders|order queue)$", FLAGS);
    private static final Pattern MARK_SOLD_OUT = Pattern.compile("^mark\\s+(.+?)\\s+(?:as\\s+)?sold\\s*out$", FLAGS);
    private static final Pattern ACCEPT = Pattern.compile(
            "^accept\\s+(?:order\\s+)?(\\S+)\\s+in\\s+(\\d+)\\s*(?:min|mins|minutes)?$", FLAGS);
    // delivery
    private static final Pattern ACTIVE_DELIVERY = Pattern.compile("^(?:my\\s+)?active\\s+delivery$", FLAGS);
    private static final Pattern DELIVERIES = Pattern.compile("^deliveries$", FLAGS);
    /** "how many deliveries are assigned to me". */
    private static final Pattern ASSIGNED_TO_ME = Pattern.compile(
            "^how\\s+many\\s+deliveries\\s+(?:are|have\\s+been)\\s+assigned\\s+to\\s+me$", FLAGS);
    private static final Pattern REASSIGN = Pattern.compile("^reassign\\s+(?:order\\s+)?(\\S+)$", FLAGS);
    // payment
    /** "payment of GA-…", "payment status for order GA-…", "refund for GA-…". */
    private static final Pattern PAYMENT_OF = Pattern.compile(
            "^(?:payment|payment\\s+status|refund|refund\\s+status)\\s+(?:of|for)\\s+(?:order\\s+)?(\\S+)$",
            FLAGS);
    private static final Pattern LATEST_PAYMENT = Pattern.compile("^(?:did\\s+my\\s+(?:last|latest)\\s+payment"
            + "\\s+go\\s+through|why\\s+did\\s+my\\s+payment\\s+fail|my\\s+(?:last|latest)\\s+payment)$", FLAGS);
    private static final Pattern TO_PAY = Pattern.compile(
            "^(?:which\\s+orders\\s+do\\s+i\\s+(?:still\\s+)?have\\s+to\\s+pay|unpaid\\s+orders)$", FLAGS);
    private static final Pattern MY_REFUNDS = Pattern.compile("^(?:my\\s+refunds|where\\s+is\\s+my\\s+refund)$",
            FLAGS);
    // support
    private static final Pattern TRACK = Pattern.compile(
            "^(?:track|where\\s+is)\\s+(?:order\\s+)?([a-z]{2,4}-\\S+)$", FLAGS);
    private static final Pattern WHERE_IS_MY_ORDER = Pattern.compile("^(?:where\\s+is\\s+my\\s+order|track\\s+my"
            + "\\s+order)$", FLAGS);
    private static final Pattern MY_RETURNS = Pattern.compile("^(?:my\\s+returns|what\\s+happened\\s+to\\s+my"
            + "\\s+return|returns\\s+of\\s+(\\S+))$", FLAGS);
    /** "complaint delivery about GA-…: the order came two hours late". */
    private static final Pattern COMPLAINT = Pattern.compile(
            "^complaint\\s+(\\w+)(?:\\s+about\\s+(\\S+))?\\s*:\\s*(.+)$", FLAGS);
    private static final Pattern MY_COMPLAINTS = Pattern.compile("^(?:my\\s+complaints|did\\s+support\\s+answer"
            + "(?:\\s+my\\s+complaint)?|status\\s+of\\s+my\\s+complaint)$", FLAGS);
    // every agent
    private static final Pattern USE_TOOL = Pattern.compile("^use\\s+tool\\s+(\\S+)\\s*(.*)$", FLAGS);

    /** Lookups that only find what the next call uses: not described when another call follows. */
    private static final Set<String> LOOKUPS = Set.of("searchProducts", "listInventory", "listProducts", "getMenu",
            "listMyOrders", "listMyPayments");
    private static final Set<String> CUSTOMER_CONFIRMS = Set.of("placeOrder", "cancelOrder", "requestReturn");
    private static final Set<String> STORE_APPROVES = Set.of("adjustStock", "changePrice", "sendPurchaseOrder",
            "setMenuItemAvailability", "acceptOrder", "rejectOrder");

    /** The agent the request is for, from its tools. */
    enum Agent { SHOPPING, ADMIN, VENDOR, RESTAURANT, DELIVERY, PAYMENT, SUPPORT }

    private final JsonMapper jsonMapper;
    private final AtomicLong callIds = new AtomicLong();

    public ScriptedChatModel(JsonMapper jsonMapper) {
        this.jsonMapper = jsonMapper;
    }

    @Override
    public ChatOptions getOptions() {
        return ToolCallingChatOptions.builder().model(MODEL).build();
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        Reply reply = reply(prompt);
        return reply.toolCall() != null ? toolCallResponse(reply.toolCall())
                : textResponse(reply.text(), "STOP");
    }

    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        return Flux.defer(() -> {
            Reply reply = reply(prompt);
            if (reply.toolCall() != null) {
                return Flux.just(toolCallResponse(reply.toolCall()));
            }
            String[] words = reply.text().split("(?<= )");
            List<ChatResponse> chunks = new ArrayList<>();
            for (int i = 0; i < words.length; i++) {
                chunks.add(textResponse(words[i], i == words.length - 1 ? "STOP" : null));
            }
            return Flux.fromIterable(chunks);
        });
    }

    // ---- decision -------------------------------------------------------------------------------------------------

    /** Either the next tool call, or the final text. */
    record Reply(AssistantMessage.ToolCall toolCall, String text) {
    }

    /** A tool call the script wants to make: name and JSON arguments. */
    record Call(String name, String arguments) {
    }

    /** One finished tool call of this turn. */
    record Result(String name, JsonNode json) {

        boolean ok() {
            return json != null && json.isObject() && !json.has("status");
        }
    }

    /** A rule: the next call given the results so far (empty = answer now), and how to answer. */
    private record Plan(List<Function<List<Result>, Optional<Call>>> steps, Function<List<Result>, String> answer) {
    }

    Reply reply(Prompt prompt) {
        List<Message> messages = prompt.getInstructions();
        int lastUser = -1;
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (messages.get(i) instanceof UserMessage) {
                lastUser = i;
                break;
            }
        }
        String userText = lastUser < 0 ? "" : Objects.requireNonNullElse(messages.get(lastUser).getText(), "");
        if (userText.contains(InsightsService.DATA_BEGIN)) {
            return new Reply(null, insights(userText));
        }
        List<Result> results = new ArrayList<>();
        int calls = 0;
        for (int i = lastUser + 1; i < messages.size(); i++) {
            if (messages.get(i) instanceof AssistantMessage assistant && assistant.hasToolCalls()) {
                calls += assistant.getToolCalls().size();
            }
            if (messages.get(i) instanceof ToolResponseMessage responses) {
                for (ToolResponseMessage.ToolResponse response : responses.getResponses()) {
                    results.add(new Result(response.name(), parse(response.responseData())));
                }
            }
        }
        Plan plan = plan(normalize(userText), agent(toolNames(prompt)));
        if (calls < plan.steps().size()) {
            Optional<Call> next = plan.steps().get(calls).apply(results);
            if (next.isPresent()) {
                return new Reply(new AssistantMessage.ToolCall("call_" + callIds.incrementAndGet(), "function",
                        next.get().name(), next.get().arguments()), null);
            }
        }
        return new Reply(null, plan.answer().apply(results));
    }

    static Agent agent(Set<String> tools) {
        if (tools.contains("getOrderStats")) {
            return Agent.ADMIN;
        }
        if (tools.contains("listInventory")) {
            return Agent.VENDOR;
        }
        if (tools.contains("getOrderQueue")) {
            return Agent.RESTAURANT;
        }
        if (tools.contains("getActiveDelivery") || tools.contains("listDeliveries")) {
            return Agent.DELIVERY;
        }
        if (tools.contains("listMyPayments")) {
            return Agent.PAYMENT;
        }
        if (tools.contains("fileComplaint")) {
            return Agent.SUPPORT;
        }
        return Agent.SHOPPING;
    }

    private Plan plan(String text, Agent agent) {
        Matcher m = USE_TOOL.matcher(text);
        if (m.matches()) {
            String arguments = m.group(2).isBlank() ? "{}" : m.group(2).strip();
            return single(new Call(m.group(1), arguments));
        }
        Plan plan = switch (agent) {
            case SHOPPING -> shopping(text);
            case ADMIN -> admin(text);
            case VENDOR -> vendor(text);
            case RESTAURANT -> restaurant(text);
            case DELIVERY -> delivery(text);
            case PAYMENT -> payment(text);
            case SUPPORT -> support(text);
        };
        return plan != null ? plan : new Plan(List.of(), results -> help(agent));
    }

    private Plan shopping(String text) {
        Matcher m;
        if ((m = ADD.matcher(text)).matches()) {
            int quantity = Integer.parseInt(m.group(1).length() > 6 ? "999999" : m.group(1));
            String words = m.group(2).strip();
            return new Plan(List.of(
                    results -> Optional.of(new Call("searchProducts", json(o -> {
                        o.put("query", words);
                        o.put("inStockOnly", true);
                    }))),
                    results -> {
                        JsonNode first = results.isEmpty() ? null : first(results.getLast().json(), "products");
                        return first == null ? Optional.empty() : Optional.of(new Call("addToCart",
                                json(o -> {
                                    o.put("productId", Json.text(first, "id"));
                                    o.put("quantity", quantity);
                                })));
                    }), this::answer);
        }
        if ((m = IN_CATEGORY.matcher(text)).matches()) {
            String words = m.group(1).strip();
            String category = m.group(2).strip();
            return single(new Call("searchProducts", json(o -> {
                o.put("query", words);
                o.put("category", category);
            })));
        }
        if (LATEST_ORDER.matcher(text).matches()) {
            return new Plan(List.of(
                    results -> Optional.of(new Call("listMyOrders", "{}")),
                    results -> {
                        JsonNode newest = results.isEmpty() ? null : first(results.getLast().json(), "orders");
                        return newest == null ? Optional.empty() : Optional.of(new Call("getOrderStatus",
                                json(o -> o.put("orderNumber", Json.text(newest, "orderNumber")))));
                    }),
                    results -> results.size() == 1 && results.getFirst().ok()
                            ? "You have no orders yet." : answer(results));
        }
        if ((m = PRICED.matcher(text)).matches()) {
            String words = m.group(1).strip();
            String query = ANY_PRODUCT.matcher(words).matches() ? null : words;
            String maxPrice = m.group(2);
            return single(new Call("searchProducts", json(o -> {
                if (query != null) {
                    o.put("query", query);
                }
                o.put("maxPrice", maxPrice);
                if (query == null) {
                    o.put("sort", "PRICE_ASC");
                }
            })));
        }
        if ((m = SEARCH.matcher(text)).matches()) {
            String words = m.group(1).strip();
            return single(new Call("searchProducts", json(o -> o.put("query", words))));
        }
        if (CART.matcher(text).matches()) {
            return single(new Call("getCart", "{}"));
        }
        if (CHECKOUT.matcher(text).matches()) {
            return new Plan(List.of(
                    results -> Optional.of(new Call("previewCheckout", "{}")),
                    results -> results.isEmpty() || !results.getLast().ok() ? Optional.empty()
                            : Optional.of(new Call("placeOrder",
                            json(o -> o.put("rationale", "You asked me to check out."))))), this::answer);
        }
        if ((m = CANCEL_ORDER.matcher(text)).matches()) {
            String number = m.group(1);
            return single(new Call("cancelOrder", json(o -> {
                o.put("orderNumber", number);
                o.put("reason", "Requested through the assistant");
                o.put("rationale", "You asked me to cancel order " + number + ".");
            })));
        }
        if ((m = STATUS_OF.matcher(text)).matches()) {
            String number = m.group(1);
            return single(new Call("getOrderStatus", json(o -> o.put("orderNumber", number))));
        }
        if ((m = RETURN.matcher(text)).matches()) {
            int quantity = Integer.parseInt(m.group(1).length() > 6 ? "999999" : m.group(1));
            String words = m.group(2).strip();
            String number = m.group(3);
            String because = m.group(4).strip();
            return single(new Call("requestReturn", json(o -> {
                o.put("orderNumber", number);
                ArrayNode items = o.putArray("items");
                items.addObject().put("name", words).put("quantity", quantity);
                o.put("reason", returnReason(because));
                o.put("note", because);
                o.put("rationale", "You asked me to return " + quantity + " " + words + " from " + number + ".");
            })));
        }
        return null;
    }

    private Plan payment(String text) {
        Matcher m;
        if ((m = PAYMENT_OF.matcher(text)).matches()) {
            String number = m.group(1);
            return single(new Call("getPaymentStatus", json(o -> o.put("orderNumber", number))));
        }
        if (LATEST_PAYMENT.matcher(text).matches()) {
            return new Plan(List.of(
                    results -> Optional.of(new Call("listMyPayments", "{}")),
                    results -> {
                        JsonNode newest = results.isEmpty() ? null : first(results.getLast().json(), "orders");
                        return newest == null ? Optional.empty() : Optional.of(new Call("getPaymentStatus",
                                json(o -> o.put("orderNumber", Json.text(newest, "orderNumber")))));
                    }),
                    results -> results.size() == 1 && results.getFirst().ok()
                            ? "You have no orders yet." : answer(results));
        }
        if (TO_PAY.matcher(text).matches()) {
            return single(new Call("listMyPayments", json(o -> o.put("filter", "WAITING_FOR_PAYMENT"))));
        }
        if (MY_REFUNDS.matcher(text).matches()) {
            return single(new Call("listMyPayments", json(o -> o.put("filter", "REFUNDS"))));
        }
        return CANCEL_ORDER.matcher(text).matches() ? shopping(text) : null;
    }

    private Plan support(String text) {
        Matcher m;
        if ((m = TRACK.matcher(text)).matches()) {
            String number = m.group(1);
            return single(new Call("trackOrder", json(o -> o.put("orderNumber", number))));
        }
        if (WHERE_IS_MY_ORDER.matcher(text).matches()) {
            return new Plan(List.of(
                    results -> Optional.of(new Call("listMyOrders", "{}")),
                    results -> {
                        JsonNode newest = results.isEmpty() ? null : first(results.getLast().json(), "orders");
                        return newest == null ? Optional.empty() : Optional.of(new Call("trackOrder",
                                json(o -> o.put("orderNumber", Json.text(newest, "orderNumber")))));
                    }),
                    results -> results.size() == 1 && results.getFirst().ok()
                            ? "You have no orders yet." : answer(results));
        }
        if ((m = MY_RETURNS.matcher(text)).matches()) {
            String number = m.group(1);
            return single(new Call("listMyReturns", number == null ? "{}"
                    : json(o -> o.put("orderNumber", number))));
        }
        if ((m = COMPLAINT.matcher(text)).matches()) {
            String category = m.group(1);
            String number = m.group(2);
            String description = m.group(3).strip();
            return single(new Call("fileComplaint", json(o -> {
                o.put("category", category);
                if (number != null) {
                    o.put("orderNumber", number);
                }
                o.put("description", description);
            })));
        }
        if (MY_COMPLAINTS.matcher(text).matches()) {
            return single(new Call("listMyComplaints", "{}"));
        }
        // The order tools it shares with the shopping assistant.
        boolean orderRequest = CANCEL_ORDER.matcher(text).matches() || STATUS_OF.matcher(text).matches()
                || RETURN.matcher(text).matches() || LATEST_ORDER.matcher(text).matches();
        return orderRequest ? shopping(text) : null;
    }

    private Plan admin(String text) {
        Matcher m;
        if (STATS.matcher(text).matches()) {
            return single(new Call("getOrderStats", "{}"));
        }
        if (STUCK.matcher(text).matches()) {
            return single(new Call("listStuckOrders", "{}"));
        }
        if ((m = REFUND.matcher(text)).matches()) {
            String amount = m.group(1);
            String number = m.group(2);
            String note = m.group(3).strip();
            return single(new Call("refundOrder", json(o -> {
                o.put("orderNumber", number);
                if (!"all".equalsIgnoreCase(amount)) {
                    o.put("amount", amount.replace("₹", ""));
                }
                o.put("note", note);
                o.put("rationale", "Requested in the admin assistant: " + note);
            })));
        }
        if ((m = SUSPEND.matcher(text)).matches()) {
            boolean vendor = "vendor".equalsIgnoreCase(m.group(1));
            String id = m.group(2);
            String reason = m.group(3).strip();
            return single(new Call(vendor ? "suspendVendor" : "suspendRestaurant", json(o -> {
                o.put(vendor ? "vendorId" : "restaurantId", id);
                o.put("reason", reason);
            })));
        }
        if ((m = ADMIN_CANCEL.matcher(text)).matches()) {
            String number = m.group(1);
            String reason = m.group(2).strip();
            return single(new Call("cancelOrderAsAdmin", json(o -> {
                o.put("orderNumber", number);
                o.put("reason", reason);
            })));
        }
        if ((m = HOW_MANY_PARTNERS.matcher(text)).matches()) {
            String type = partnerType(m.group(1));
            return single(new Call("getStakeholderCounts", json(o -> {
                if (type != null) {
                    o.put("type", type);
                }
            })));
        }
        if ((m = LIST_PARTNERS.matcher(text)).matches()) {
            String status = m.group(1) == null ? null : m.group(1).toLowerCase(Locale.ROOT).equals("pending")
                    ? "PENDING_APPROVAL" : m.group(1).toUpperCase(Locale.ROOT);
            String type = partnerType(m.group(2));
            return single(new Call("listStakeholders", json(o -> {
                o.put("type", type);
                if (status != null) {
                    o.put("status", status);
                }
            })));
        }
        return null;
    }

    /** "vendors" → VENDOR, "delivery partner" → DELIVERY_PARTNER, "customers" → null (count everything). */
    static String partnerType(String words) {
        String word = words.toLowerCase(Locale.ROOT);
        if (word.startsWith("vendor")) {
            return "VENDOR";
        }
        if (word.startsWith("restaurant")) {
            return "RESTAURANT";
        }
        return word.contains("partner") ? "DELIVERY_PARTNER" : null;
    }

    private Plan vendor(String text) {
        Matcher m;
        if (LOW_STOCK.matcher(text).matches()) {
            return single(new Call("listInventory", json(o -> o.put("filter", "LOW_STOCK"))));
        }
        if (SALES.matcher(text).matches()) {
            return single(new Call("getSalesReport", "{}"));
        }
        if (ORDERS_TODAY.matcher(text).matches()) {
            String today = LocalDate.now(INDIA).toString();
            return single(new Call("getSalesReport", json(o -> {
                o.put("from", today);
                o.put("to", today);
            })));
        }
        if ((m = SET_STOCK.matcher(text)).matches()) {
            String words = m.group(1).strip();
            int quantity = Integer.parseInt(m.group(2).length() > 7 ? "99999999" : m.group(2));
            return new Plan(List.of(
                    results -> Optional.of(new Call("listInventory", json(o -> o.put("query", words)))),
                    results -> {
                        JsonNode first = results.isEmpty() ? null : first(results.getLast().json(), "products");
                        return first == null ? Optional.empty() : Optional.of(new Call("adjustStock", json(o -> {
                            o.put("productId", Json.text(first, "productId"));
                            o.put("type", "SET");
                            o.put("quantity", quantity);
                            o.put("reason", "STOCK_COUNT");
                            o.put("rationale", "You asked me to set the stock of " + Json.text(first, "productName")
                                    + " to " + quantity + ".");
                        })));
                    }), this::answer);
        }
        if ((m = PRICE.matcher(text)).matches()) {
            String words = m.group(1).strip();
            String amount = m.group(2);
            return new Plan(List.of(
                    results -> Optional.of(new Call("listProducts", json(o -> o.put("query", words)))),
                    results -> {
                        JsonNode first = results.isEmpty() ? null : first(results.getLast().json(), "products");
                        return first == null ? Optional.empty() : Optional.of(new Call("changePrice", json(o -> {
                            o.put("productId", Json.text(first, "id"));
                            o.put("sellingPrice", amount);
                            o.put("rationale", "You asked me to change the price of " + Json.text(first, "name")
                                    + " to ₹" + amount + ".");
                        })));
                    }), this::answer);
        }
        return null;
    }

    private Plan restaurant(String text) {
        Matcher m;
        if (ORDERS.matcher(text).matches()) {
            return single(new Call("getOrderQueue", "{}"));
        }
        if (SALES.matcher(text).matches()) {
            return single(new Call("getSalesReport", "{}"));
        }
        if ((m = MARK_SOLD_OUT.matcher(text)).matches()) {
            String words = m.group(1).strip();
            return new Plan(List.of(
                    results -> Optional.of(new Call("getMenu", "{}")),
                    results -> {
                        JsonNode dish = results.isEmpty() ? null : dish(results.getLast().json(), words);
                        return dish == null ? Optional.empty() : Optional.of(new Call("setMenuItemAvailability",
                                json(o -> {
                                    o.put("menuItemId", Json.text(dish, "id"));
                                    o.put("available", false);
                                    o.put("rationale", "You asked me to mark " + Json.text(dish, "name")
                                            + " sold out.");
                                })));
                    }),
                    results -> results.size() == 1 && "getMenu".equals(results.getFirst().name())
                            && results.getFirst().ok()
                            ? "I couldn't find a dish matching “" + words + "” on your menu."
                            : answer(results));
        }
        if ((m = ACCEPT.matcher(text)).matches()) {
            String number = m.group(1);
            int minutes = Integer.parseInt(m.group(2).length() > 6 ? "999999" : m.group(2));
            return single(new Call("acceptOrder", json(o -> {
                o.put("orderNumber", number);
                o.put("readyInMinutes", minutes);
                o.put("rationale", "You asked me to accept order " + number + " with " + minutes
                        + " minutes to prepare.");
            })));
        }
        return null;
    }

    private Plan delivery(String text) {
        Matcher m;
        if (ACTIVE_DELIVERY.matcher(text).matches()) {
            return single(new Call("getActiveDelivery", "{}"));
        }
        if (DELIVERIES.matcher(text).matches()) {
            return single(new Call("listDeliveries", "{}"));
        }
        if (ASSIGNED_TO_ME.matcher(text).matches()) {
            return new Plan(List.of(
                    results -> Optional.of(new Call("getActiveDelivery", "{}")),
                    results -> results.isEmpty() || !results.getLast().ok() ? Optional.empty()
                            : Optional.of(new Call("getMyOffers", "{}"))), this::answer);
        }
        if ((m = REASSIGN.matcher(text)).matches()) {
            String number = m.group(1);
            return single(new Call("reassignDelivery", json(o -> {
                o.put("orderNumber", number);
                o.put("reason", "Requested through the assistant");
                o.put("rationale", "You asked me to reassign the delivery of order " + number + ".");
            })));
        }
        return null;
    }

    private Plan single(Call call) {
        return new Plan(List.of(results -> Optional.of(call)), this::answer);
    }

    /** The reason a customer gives, as a model would classify it. */
    static String returnReason(String because) {
        String text = because.toLowerCase(Locale.ROOT);
        if (containsAny(text, "damag", "broken", "crush", "leak", "torn", "dent")) {
            return "DAMAGED";
        }
        if (containsAny(text, "wrong", "different", "not what i ordered")) {
            return "WRONG_ITEM";
        }
        if (containsAny(text, "missing", "never arrived", "not delivered", "not received", "didn't get",
                "did not get")) {
            return "MISSING";
        }
        if (containsAny(text, "quality", "stale", "rotten", "expired", "spoil", "bad", "fresh", "taste", "smell",
                "cold")) {
            return "QUALITY";
        }
        return "OTHER";
    }

    private static boolean containsAny(String text, String... words) {
        for (String word : words) {
            if (text.contains(word)) {
                return true;
            }
        }
        return false;
    }

    /** The dish of a getMenu result whose name is {@code words} (ignoring case), else the first containing them all. */
    static JsonNode dish(JsonNode menuResult, String words) {
        String wanted = words.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").strip();
        List<JsonNode> dishes = new ArrayList<>();
        for (JsonNode category : Json.list(menuResult, "menu.categories")) {
            dishes.addAll(Json.list(category, "items"));
        }
        for (JsonNode dish : dishes) {
            if (wanted.equals(Objects.requireNonNullElse(Json.text(dish, "name"), "").toLowerCase(Locale.ROOT)
                    .strip())) {
                return dish;
            }
        }
        for (JsonNode dish : dishes) {
            String name = Objects.requireNonNullElse(Json.text(dish, "name"), "").toLowerCase(Locale.ROOT);
            boolean all = true;
            for (String word : wanted.split(" ")) {
                all &= name.contains(word);
            }
            if (all) {
                return dish;
            }
        }
        return null;
    }

    // ---- answers ------------------------------------------------------------------------------------------------

    private String answer(List<Result> results) {
        if (results.isEmpty()) {
            return "I could not do anything with that.";
        }
        List<String> sentences = new ArrayList<>();
        for (int i = 0; i < results.size(); i++) {
            Result result = results.get(i);
            boolean last = i == results.size() - 1;
            if (!last && LOOKUPS.contains(result.name()) && result.ok()) {
                continue;                               // the lookup only found what the next call used
            }
            if (!last && "previewCheckout".equals(result.name()) && result.ok()) {
                sentences.add("Your order total is " + Money.inr(Json.decimal(result.json(), "preview.total"))
                        + ".");
                continue;
            }
            sentences.add(describe(result));
        }
        return String.join(" ", sentences);
    }

    private String describe(Result result) {
        JsonNode json = result.json();
        String status = Json.text(json, "status");
        if ("APPROVAL_REQUIRED".equals(status)) {
            String who = CUSTOMER_CONFIRMS.contains(result.name()) ? "you confirm it in the app"
                    : STORE_APPROVES.contains(result.name()) ? "you approve it in the app"
                    : "reassignDelivery".equals(result.name()) ? "a delivery manager or operations admin approves it"
                    : "an authorised admin approves it";
            return "I've prepared “" + Json.text(json, "title") + "”. Nothing happens until " + who
                    + " (it expires at " + time(Json.text(json, "expiresAt")) + ")."
                    + ("changePrice".equals(result.name())
                    ? " A change above the price threshold then still waits for an admin." : "");
        }
        if ("BLOCKED".equals(status)) {
            return "I can't do that: " + Json.text(json, "message");
        }
        if ("ERROR".equals(status)) {
            return "That didn't work: " + Json.text(json, "message") + " (" + Json.text(json, "code") + ")";
        }
        return switch (result.name()) {
            case "searchProducts" -> {
                List<JsonNode> products = Json.list(json, "products");
                String what = (Json.text(json, "query") == null ? "" : " for “" + Json.text(json, "query") + "”")
                        + Money.range(Json.decimal(json, "minPrice"), Json.decimal(json, "maxPrice"));
                if (products.isEmpty()) {
                    yield "I couldn't find any products" + what + ".";
                }
                List<String> lines = new ArrayList<>();
                for (int i = 0; i < products.size(); i++) {
                    JsonNode p = products.get(i);
                    lines.add((i + 1) + ". " + Json.text(p, "name")
                            + (Json.text(p, "unitLabel") == null ? "" : " (" + Json.text(p, "unitLabel") + ")")
                            + " – " + Money.inr(Json.decimal(p, "price"))
                            + (Json.at(p, "inStock") != null && !Json.bool(p, "inStock") ? ", out of stock" : ""));
                }
                Integer total = Json.integer(json, "totalFound");
                if (total != null && total > products.size()) {
                    yield "I found " + total + " products" + what + ". The first " + products.size() + ": "
                            + String.join("; ", lines) + ".";
                }
                yield "I found: " + String.join("; ", lines) + ".";
            }
            case "addToCart" -> "Added " + Json.integer(json, "added.quantity") + " × "
                    + Objects.requireNonNullElse(Json.text(json, "added.name"), "the item") + " to your cart. "
                    + cart(Json.at(json, "cart"));
            case "updateCartItem" -> "Updated your cart. " + cart(Json.at(json, "cart"));
            case "getCart" -> cart(Json.at(json, "cart"));
            case "previewCheckout" -> Json.bool(json, "preview.canCheckout")
                    ? "Your order total is " + Money.inr(Json.decimal(json, "preview.total")) + " (subtotal "
                    + Money.inr(Json.decimal(json, "preview.subtotal")) + ", delivery "
                    + Money.inr(Json.decimal(json, "preview.deliveryFee")) + ")."
                    : "Checkout is blocked: " + Json.list(json, "preview.issues").stream()
                    .map(issue -> Json.text(issue, "message")).collect(Collectors.joining("; ")) + ".";
            case "getOrderStatus", "getOrder" -> "Order " + Json.text(json, "order.orderNumber") + " is "
                    + Json.text(json, "order.status") + " (total " + Money.inr(Json.decimal(json, "order.total"))
                    + ").";
            case "getOrderStats" -> "On " + Json.text(json, "stats.date") + ": " + Json.integer(json, "stats.orders")
                    + " orders, confirmed revenue " + Money.inr(Json.decimal(json, "stats.confirmedRevenue"))
                    + ", average order value " + Money.inr(Json.decimal(json, "stats.averageOrderValue")) + ".";
            case "listStuckOrders" -> {
                List<JsonNode> stuck = Json.list(json, "stuckOrders");
                if (stuck.isEmpty()) {
                    yield "No orders are stuck right now.";
                }
                yield Json.integer(json, "count") + " stuck orders: " + stuck.stream()
                        .map(o -> Json.text(o, "orderNumber") + " (" + Json.text(o, "status") + ", "
                                + Json.text(o, "reason") + ", " + Json.integer(o, "minutesOverdue") + " min overdue)")
                        .collect(Collectors.joining("; ")) + ".";
            }
            case "listInventory" -> inventory(json);
            case "getStakeholderCounts" -> stakeholderCounts(json);
            case "listStakeholders" -> stakeholders(json);
            case "getMyOffers" -> {
                int offers = Json.list(json, "offers").size();
                yield offers == 0 ? "No delivery offers are waiting for you."
                        : offers + (offers == 1 ? " offer is" : " offers are") + " waiting for your answer.";
            }
            case "listProducts" -> {
                List<JsonNode> products = Json.list(json, "products");
                if (products.isEmpty()) {
                    yield "I couldn't find any products" + (Json.text(json, "query") == null ? ""
                            : " matching “" + Json.text(json, "query") + "”") + ".";
                }
                yield "Your products: " + products.stream().map(p -> Json.text(p, "name")
                        + (Json.text(p, "unitLabel") == null ? "" : " (" + Json.text(p, "unitLabel") + ")") + ", "
                        + Json.text(p, "status")).collect(Collectors.joining("; ")) + ".";
            }
            case "getSalesReport" -> "From " + Json.text(json, "report.from") + " to " + Json.text(json, "report.to")
                    + ": " + Json.integer(json, "report.ordersReceived") + " orders received, "
                    + Json.integer(json, "report.ordersAccepted") + " accepted, gross sales "
                    + Money.inr(Json.decimal(json, "report.grossSales")) + ", average order value "
                    + Money.inr(Json.decimal(json, "report.averageOrderValue")) + ".";
            case "getOrderQueue", "listStoreOrders" -> {
                List<JsonNode> orders = Json.list(json, "orders");
                if (orders.isEmpty()) {
                    yield "There are no orders right now.";
                }
                yield orders.size() + (orders.size() == 1 ? " order: " : " orders: ") + orders.stream()
                        .map(o -> Json.text(o, "orderNumber") + " (" + Json.text(o, "status") + ", "
                                + Json.integer(o, "itemCount")
                                + (Objects.equals(Json.integer(o, "itemCount"), 1) ? " item, " : " items, ")
                                + Money.inr(Json.decimal(o, "total"))
                                + ("NEW".equals(Json.text(o, "status")) && Json.text(o, "acceptBy") != null
                                ? ", accept by " + time(Json.text(o, "acceptBy")) : "") + ")")
                        .collect(Collectors.joining("; ")) + ".";
            }
            case "getMenu" -> {
                int dishes = 0;
                int soldOut = 0;
                for (JsonNode category : Json.list(json, "menu.categories")) {
                    for (JsonNode dish : Json.list(category, "items")) {
                        dishes++;
                        soldOut += Json.bool(dish, "available") ? 0 : 1;
                    }
                }
                yield "Your menu has " + dishes + " dishes, " + soldOut + " sold out.";
            }
            case "getActiveDelivery" -> !Json.bool(json, "active") ? "You have no active delivery right now."
                    : "Your active delivery: order " + Json.text(json, "delivery.orderNumber") + " ("
                    + Json.text(json, "delivery.status") + "), pick up at " + Json.text(json, "delivery.pickup.name")
                    + ", drop at " + Json.text(json, "delivery.drop.line1") + ", "
                    + Json.text(json, "delivery.drop.city") + ".";
            case "listDeliveries" -> {
                List<JsonNode> deliveries = Json.list(json, "deliveries");
                if (deliveries.isEmpty()) {
                    yield "There are no deliveries.";
                }
                yield deliveries.size() + (deliveries.size() == 1 ? " delivery: " : " deliveries: ")
                        + deliveries.stream().map(d -> Json.text(d, "orderNumber") + " (" + Json.text(d, "status")
                                + (Json.text(d, "partnerName") != null ? ", " + Json.text(d, "partnerName") : "")
                                + (Json.bool(d, "assignmentFailed") ? ", no partner found" : "") + ")")
                        .collect(Collectors.joining("; ")) + ".";
            }
            case "listMyPayments" -> {
                List<JsonNode> orders = Json.list(json, "orders");
                if (orders.isEmpty()) {
                    yield "None of your recent orders match.";
                }
                yield orders.size() + (orders.size() == 1 ? " order: " : " orders: ") + orders.stream()
                        .map(o -> Json.text(o, "orderNumber") + " (" + Json.text(o, "paymentStatusText")
                                + (Json.text(o, "payBy") == null ? "" : ", pay by " + Json.text(o, "payBy"))
                                + ", " + Money.inr(Json.decimal(o, "total")) + ")")
                        .collect(Collectors.joining("; ")) + ".";
            }
            case "getPaymentStatus" -> Json.text(json, "whatHappened") + " " + Json.text(json, "nextStep");
            case "trackOrder" -> "Order " + Json.text(json, "orderNumber") + " is " + Json.text(json, "orderStatusText")
                    + (Json.text(json, "cancelledBecause") == null ? ""
                    : " (because " + Json.text(json, "cancelledBecause") + ")")
                    + (Json.text(json, "delivery.partnerName") == null ? ""
                    : ", with " + Json.text(json, "delivery.partnerName"))
                    + (Json.integer(json, "delivery.etaMinutes") == null ? ""
                    : ", arriving in about " + Json.integer(json, "delivery.etaMinutes") + " min") + ".";
            case "listMyReturns" -> {
                List<JsonNode> returns = Json.list(json, "returns");
                if (returns.isEmpty()) {
                    yield "You have no return requests" + (Json.text(json, "orderNumber") == null ? ""
                            : " for " + Json.text(json, "orderNumber")) + ".";
                }
                yield returns.stream().map(r -> Json.text(r, "returnNumber") + " for " + Json.text(r, "orderNumber")
                                + ": " + Json.text(r, "statusText") + (Json.decimal(r, "refundAmount") == null ? ""
                                : ", refund " + Money.inr(Json.decimal(r, "refundAmount"))))
                        .collect(Collectors.joining("; ")) + ".";
            }
            case "fileComplaint" -> "I've raised complaint " + Json.text(json, "complaint.complaintNumber")
                    + " (" + Json.text(json, "complaint.categoryText") + "). Our support team answers it in the app "
                    + "under Help & support.";
            case "listMyComplaints" -> {
                List<JsonNode> complaints = Json.list(json, "complaints");
                if (complaints.isEmpty()) {
                    yield "You have no complaints.";
                }
                String list = complaints.stream().map(c -> Json.text(c, "complaintNumber")
                                + (Json.text(c, "orderNumber") == null ? "" : " about " + Json.text(c, "orderNumber"))
                                + ": " + Json.text(c, "statusText")
                                + (Json.text(c, "answer") == null ? "" : " — answer: " + Json.text(c, "answer")))
                        .collect(Collectors.joining("; "));
                yield list.matches("(?s).*[.!?]$") ? list : list + ".";   // an answer may end its own sentence
            }
            default -> {
                String compact = jsonMapper.writeValueAsString(json);
                yield "Result of " + result.name() + ": " + (compact.length() > 300 ? compact.substring(0, 299) + "…"
                        : compact);
            }
        };
    }

    /** "Vendors: 112 approved, 5 waiting for approval, 23 suspended, 0 rejected, 3 not submitted yet (143 in all)." */
    private static String stakeholderCounts(JsonNode json) {
        List<String> parts = new ArrayList<>();
        for (String[] kind : new String[][]{{"vendors", "Vendors"}, {"restaurants", "Restaurants"},
                {"deliveryPartners", "Delivery partners"}}) {
            JsonNode counts = Json.at(json, kind[0]);
            if (counts == null) {
                continue;
            }
            parts.add(kind[1] + ": " + Json.integer(counts, "byStatus.APPROVED") + " approved, "
                    + Json.integer(counts, "byStatus.PENDING_APPROVAL") + " waiting for approval, "
                    + Json.integer(counts, "byStatus.SUSPENDED") + " suspended, "
                    + Json.integer(counts, "byStatus.REJECTED") + " rejected, "
                    + Json.integer(counts, "byStatus.DRAFT") + " not submitted yet ("
                    + Json.integer(counts, "total") + " in all).");
        }
        if (Json.integer(json, "customers") != null) {
            parts.add("Customers: " + Json.integer(json, "customers") + ".");
        }
        return parts.isEmpty() ? "I couldn't count them." : String.join(" ", parts);
    }

    /** "5 vendors waiting for approval: Fresh Mart (Pune), …". */
    private static String stakeholders(JsonNode json) {
        String type = Objects.requireNonNullElse(Json.text(json, "type"), "VENDOR");
        String key = switch (type) {
            case "RESTAURANT" -> "restaurants";
            case "DELIVERY_PARTNER" -> "deliveryPartners";
            default -> "vendors";
        };
        String noun = "deliveryPartners".equals(key) ? "delivery partners" : key;
        String status = Json.text(json, "status");
        String which = status == null ? "" : "PENDING_APPROVAL".equals(status) ? " waiting for approval"
                : " " + status.toLowerCase(Locale.ROOT).replace('_', ' ');
        List<JsonNode> entries = Json.list(json, key);
        if (entries.isEmpty()) {
            return "There are no " + noun + which + ".";
        }
        return Json.integer(json, "totalFound") + " " + noun + which + ": " + entries.stream()
                .map(e -> Json.text(e, "name") + (Json.text(e, "city") == null ? "" : " (" + Json.text(e, "city") + ")"))
                .collect(Collectors.joining(", ")) + ".";
    }

    private static String inventory(JsonNode json) {
        List<JsonNode> products = Json.list(json, "products");
        String filter = Json.text(json, "filter");
        String query = Json.text(json, "query");
        if (products.isEmpty()) {
            return "I couldn't find any products" + (query != null ? " matching “" + query + "”"
                    : "LOW_STOCK".equals(filter) ? " with low stock" : "OUT_OF_STOCK".equals(filter)
                    ? " out of stock" : "") + ".";
        }
        String heading = "LOW_STOCK".equals(filter) ? "Low stock: " : "OUT_OF_STOCK".equals(filter)
                ? "Out of stock: " : "Stock: ";
        return heading + products.stream().map(p -> Json.text(p, "productName")
                + (Json.text(p, "unitLabel") == null ? "" : " (" + Json.text(p, "unitLabel") + ")") + " – "
                + Json.integer(p, "available") + " available"
                + (Json.integer(p, "lowStockThreshold") != null && Json.integer(p, "lowStockThreshold") > 0
                ? " (alert at " + Json.integer(p, "lowStockThreshold") + ")" : ""))
                .collect(Collectors.joining("; ")) + ".";
    }

    private static String cart(JsonNode cart) {
        List<JsonNode> items = Json.list(cart, "items");
        if (items.isEmpty()) {
            return "Your cart is empty.";
        }
        return "Your cart from " + Objects.requireNonNullElse(Json.text(cart, "store.name"), "the store") + ": "
                + items.stream().map(i -> Json.text(i, "name") + " × " + Json.integer(i, "quantity"))
                .collect(Collectors.joining(", ")) + " — subtotal " + Money.inr(Json.decimal(cart, "subtotal"))
                + ".";
    }

    private static String help(Agent agent) {
        String rules = switch (agent) {
            case ADMIN -> "“stats” or “sales today”, “stuck orders”, “how many vendors …”, “show pending vendor "
                    + "approvals”, “refund <amount|all> for <ORDER-NUMBER> because "
                    + "<note>”, “cancel <ORDER-NUMBER> because <reason>”, “suspend vendor <id> because <reason>”, "
                    + "“suspend restaurant <id> because <reason>”";
            case VENDOR -> "“low stock”, “sales”, “how many orders did I get today”, “set stock <product> to <n>”, "
                    + "“price <product> <amount>”";
            case RESTAURANT -> "“orders”, “sales”, “mark <dish> sold out”, “accept <ORDER-NUMBER> in <n> min”";
            case DELIVERY -> "“active delivery” and “how many deliveries are assigned to me” (partners), "
                    + "“deliveries” and “reassign <ORDER-NUMBER>” (managers)";
            case PAYMENT -> "“payment of <ORDER-NUMBER>”, “did my last payment go through”, “which orders do I "
                    + "have to pay”, “my refunds”, “cancel order <ORDER-NUMBER>”";
            case SUPPORT -> "“track <ORDER-NUMBER>”, “where is my order”, “status of <ORDER-NUMBER>”, “my returns”, "
                    + "“returns of <ORDER-NUMBER>”, “complaint <category> about <ORDER-NUMBER>: <what happened>”, "
                    + "“my complaints”, “cancel order <ORDER-NUMBER>”";
            case SHOPPING -> "“add <n> <product>”, “search <words>”, “find <words> in the <name> category”, "
                    + "“products under <amount>”, “show my cart”, “checkout”, “where is my latest order”, "
                    + "“cancel order <ORDER-NUMBER>”, “status of <ORDER-NUMBER>”, "
                    + "“return <n> <item> from <ORDER-NUMBER> because <reason>”";
        };
        return "I'm the scripted assistant (no language model). I understand: " + rules
                + " and “use tool <name> <json>”.";
    }

    private String insights(String userText) {
        int begin = userText.indexOf(InsightsService.DATA_BEGIN) + InsightsService.DATA_BEGIN.length();
        int end = userText.indexOf(InsightsService.DATA_END, begin);
        JsonNode data = parse(userText.substring(begin, end < 0 ? userText.length() : end).strip());
        return jsonMapper.writeValueAsString(InsightRules.draft(data));
    }

    // ---- helpers ------------------------------------------------------------------------------------------------

    /** The first entry of a result's list ({@code products}), or null. */
    private static JsonNode first(JsonNode result, String list) {
        List<JsonNode> entries = Json.list(result, list);
        return entries.isEmpty() ? null : entries.getFirst();
    }

    private static String normalize(String text) {
        return text.strip().replaceAll("[.!?]+$", "").strip();
    }

    private JsonNode parse(String text) {
        try {
            return text == null || text.isBlank() ? NODES.objectNode() : jsonMapper.readTree(text);
        } catch (RuntimeException e) {
            return NODES.stringNode(text);
        }
    }

    private String json(java.util.function.Consumer<ObjectNode> filler) {
        ObjectNode node = NODES.objectNode();
        filler.accept(node);
        return jsonMapper.writeValueAsString(node);
    }

    private static String time(String isoInstant) {
        try {
            return DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT).format(Instant.parse(isoInstant).atZone(INDIA));
        } catch (RuntimeException e) {
            return String.valueOf(isoInstant);
        }
    }

    private static Set<String> toolNames(Prompt prompt) {
        if (prompt.getOptions() instanceof ToolCallingChatOptions options && options.getToolCallbacks() != null) {
            return options.getToolCallbacks().stream().map(ToolCallback::getToolDefinition)
                    .map(definition -> definition.name()).collect(Collectors.toSet());
        }
        return Set.of();
    }

    private static ChatResponse toolCallResponse(AssistantMessage.ToolCall call) {
        AssistantMessage message = AssistantMessage.builder().content("").toolCalls(List.of(call)).build();
        return new ChatResponse(List.of(new Generation(message,
                ChatGenerationMetadata.builder().finishReason("TOOL_CALLS").build())));
    }

    private static ChatResponse textResponse(String text, String finishReason) {
        ChatGenerationMetadata metadata = finishReason == null ? ChatGenerationMetadata.NULL
                : ChatGenerationMetadata.builder().finishReason(finishReason).build();
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text), metadata)));
    }
}
