package com.grocery.ai.tool;

import com.grocery.ai.audit.AiMetrics;
import com.grocery.ai.gateway.BusinessApi;
import com.grocery.ai.gateway.CallContext;
import com.grocery.ai.knowledge.KnowledgeMetrics;
import com.grocery.ai.knowledge.KnowledgeSource;
import com.grocery.ai.knowledge.search.KnowledgeSearch;
import com.grocery.ai.support.Money;
import com.grocery.common.security.Roles;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * {@code searchHelpCenter} (READ, every assistant): the knowledge base as a tool, for help-center questions the app did
 * not recognise before the model (docs/rag/RAG-MCP-INTEGRATION.md). It searches only documents meant for the caller
 * (their roles, from the token; never an argument the model chooses) and returns the matching articles, or says that
 * none matches so the assistant gives the controlled reply. Only offered when the knowledge base is enabled.
 */
@Component
public class KnowledgeTools {

    public static final String SEARCH_HELP_CENTER = "searchHelpCenter";

    /** Every signed-in role: each reads only its own audiences' documents. */
    static final Set<String> EVERYONE;

    static {
        Set<String> roles = new HashSet<>(Set.of(Roles.CUSTOMER, Roles.VENDOR, Roles.RESTAURANT_OWNER,
                Roles.DELIVERY_PARTNER));
        roles.addAll(Roles.ADMIN_ROLES);
        EVERYONE = Set.copyOf(roles);
    }

    public static final String GET_STORE_RULES = "getStoreRules";

    private final KnowledgeSearch search;
    private final KnowledgeMetrics metrics;
    private final JsonMapper jsonMapper;
    private final BusinessApi api;

    public KnowledgeTools(KnowledgeSearch search, KnowledgeMetrics metrics, JsonMapper jsonMapper, BusinessApi api) {
        this.search = search;
        this.metrics = metrics;
        this.jsonMapper = jsonMapper;
        this.api = api;
    }

    /** The tools, or none when the knowledge base is off. */
    public List<ToolSpec> tools() {
        if (!search.enabled()) {
            return List.of();
        }
        return List.of(searchHelpCenter(), ToolSpec.run(GET_STORE_RULES, "Checked the store rules",
                "The ordering rules admins set, as they are now: the minimum order amount, the delivery fee (a base "
                        + "fee for the first kilometres, then a fee per started kilometre) and the order amount "
                        + "above which grocery delivery is free, for grocery stores and for restaurants. Use it "
                        + "whenever one of these amounts is asked about; never take them from articles or memory.",
                RiskClass.READ, EVERYONE, ArgSchema.none(), this::getStoreRules));
    }

    private ToolSpec searchHelpCenter() {
        return ToolSpec.run(SEARCH_HELP_CENTER, "Checked the help center",
                "Searches the app's help center: its policies and how-tos (refunds, returns, cancellations, "
                        + "delivery, payment methods, accounts, registration and onboarding, guidelines, product "
                        + "storage) and FAQs. Call it for any question about the app's rules or how something works, "
                        + "and answer only from the articles it returns; if it finds none, say you couldn't find "
                        + "verified information. Never use it for the user's own orders, cart, payments or stock.",
                RiskClass.READ, EVERYONE,
                ArgSchema.builder().string("question", true, 3, 300,
                        "The question in a few words, e.g. 'refund time after cancelling a paid order'").build(),
                this::searchHelpCenter);
    }

    // ---- getStoreRules ------------------------------------------------------------------------------------------

    /** pricing-service: the delivery fees and minimum order admins set (platform settings). */
    static final String STORE_RULES_PATH = "/api/pricing/delivery-fees";

    /**
     * The store rules in force as one paragraph, for the app's note before the model ({@code KnowledgeAssist}), or
     * null when they cannot be read (the model can still call getStoreRules).
     */
    public String storeRulesText(CallContext http) {
        if (!(api.get(http, STORE_RULES_PATH) instanceof BusinessApi.Fetch.Ok ok) || ok.body() == null) {
            return null;
        }
        JsonNode fees = ok.body();
        return "Grocery stores: minimum order " + amount(fees, "groceryMinimumOrder") + "; delivery fee "
                + fee(fees, "groceryBaseFee", "groceryIncludedKm", "groceryPerExtraKm") + "; free delivery above "
                + amount(fees, "groceryFreeAbove") + ". Restaurants: no minimum order; delivery fee "
                + fee(fees, "restaurantBaseFee", "restaurantIncludedKm", "restaurantPerExtraKm")
                + "; no free delivery. The delivery fee of one order depends on its distance and is shown at checkout.";
    }

    /**
     * The delivery fees and minimum order in force (pricing-service {@code GET /api/pricing/delivery-fees}, the
     * platform settings admins change), in words the model can repeat.
     */
    private ToolResult getStoreRules(ToolCallContext call) {
        return Results.from(api.get(call.http(), STORE_RULES_PATH), ok -> {
            JsonNode fees = ok.body();
            ObjectNode result = Results.object();
            ObjectNode grocery = result.putObject("groceryStores");
            grocery.put("minimumOrder", amount(fees, "groceryMinimumOrder"));
            grocery.put("deliveryFee", fee(fees, "groceryBaseFee", "groceryIncludedKm", "groceryPerExtraKm"));
            grocery.put("freeDeliveryAbove", amount(fees, "groceryFreeAbove"));
            ObjectNode restaurants = result.putObject("restaurants");
            restaurants.put("minimumOrder", "none");
            restaurants.put("deliveryFee", fee(fees, "restaurantBaseFee", "restaurantIncludedKm",
                    "restaurantPerExtraKm"));
            restaurants.put("freeDeliveryAbove", "never (restaurants have no free delivery)");
            result.put("note", "Set by the platform's admins; they apply to new orders. The delivery fee of one "
                    + "order depends on its distance and is shown at checkout.");
            return Results.ok(result, "Minimum order " + amount(fees, "groceryMinimumOrder") + ", free delivery "
                    + "above " + amount(fees, "groceryFreeAbove"), ok);
        });
    }

    private static String amount(JsonNode fees, String field) {
        JsonNode value = fees.get(field);
        return value == null || value.isNull() ? "unknown" : Money.inr(value.decimalValue());
    }

    private static String fee(JsonNode fees, String base, String includedKm, String perExtraKm) {
        JsonNode km = fees.get(includedKm);
        String kilometres = km == null || km.isNull() ? "?" : km.decimalValue().stripTrailingZeros().toPlainString();
        return amount(fees, base) + " for the first " + kilometres + " km, then " + amount(fees, perExtraKm)
                + " per started km";
    }

    private ToolResult searchHelpCenter(ToolCallContext call) {
        KnowledgeSearch.Result found = search.forReader(call.args().string("question"), call.user().roles());
        switch (found.status()) {
            case UNAVAILABLE, DISABLED -> {
                metrics.retrieval(AiMetrics.UNKNOWN, "tool", KnowledgeMetrics.ERROR, 0);
                return new ToolResult.Failed("KNOWLEDGE_BASE_UNAVAILABLE",
                        "The help center cannot be reached right now.", null);
            }
            case NO_MATCH -> {
                metrics.retrieval(AiMetrics.UNKNOWN, "tool", KnowledgeMetrics.NO_MATCH, 0);
                ObjectNode result = jsonMapper.createObjectNode();
                result.put("found", false);
                result.put("note", "No help-center article answers this. Do not answer it from general knowledge: "
                        + "say you couldn't find verified information about it and suggest contacting customer "
                        + "support.");
                return new ToolResult.Ok(result, "No help-center article found", 200, List.of());
            }
            default -> {
                metrics.retrieval(AiMetrics.UNKNOWN, "tool", KnowledgeMetrics.HIT, found.snippets().size());
                ObjectNode result = jsonMapper.createObjectNode();
                result.put("found", true);
                ArrayNode articles = result.putArray("articles");
                for (KnowledgeSearch.Snippet snippet : found.snippets()) {
                    ObjectNode article = articles.addObject();
                    article.put("title", snippet.title());
                    article.put("text", snippet.text());
                }
                List<KnowledgeSource> sources = found.sources().stream()
                        .map(s -> new KnowledgeSource(s.documentId(), s.title(), s.category())).toList();
                String summary = sources.size() == 1 ? "Found the help-center article “" + sources.getFirst().title()
                        + "”" : "Found " + sources.size() + " help-center articles";
                return new ToolResult.Ok(result, summary, 200, List.of(), sources);
            }
        }
    }
}
