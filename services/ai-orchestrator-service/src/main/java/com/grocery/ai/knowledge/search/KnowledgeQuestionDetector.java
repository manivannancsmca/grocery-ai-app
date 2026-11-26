package com.grocery.ai.knowledge.search;

import java.util.regex.Pattern;

/**
 * Decides, with plain rules and no model, whether a message needs help-center text (docs/rag/RAG-MCP-INTEGRATION.md,
 * "Routing"). It does not route products, carts, orders or payments: the assistants' tools and ToolGuard already do
 * that, and a mistake here can never trigger an action.
 * <ul>
 *   <li>{@link Route#KNOWLEDGE}: a question about the app's rules or how it works, and nothing else ("What is your
 *       refund policy?", "Can I return groceries after 30 days?", "How do vendors register?"). The help center is
 *       searched before the model; when it has nothing, the controlled reply is given without the model.</li>
 *   <li>{@link Route#KNOWLEDGE_AND_LIVE}: such a question that also needs the user's own data or a number admins set
 *       ("What is the delivery policy and where is my order?", "Can I cancel my current order?", "What is the minimum
 *       order amount?"). The help center is searched before the model, and the model still uses its tools.</li>
 *   <li>{@link Route#MAYBE}: only general how-to wording ("How do I …", "What happens if …"), which may just as well
 *       be about products or an order. The help center is searched, and its articles are given to the model only
 *       when one matches.</li>
 *   <li>{@link Route#NONE}: everything else. Rules cannot know every way to ask, so the help center is still
 *       searched for such a message (of a few words), but its articles are given to the model only above the higher
 *       {@code unrecognised-threshold}; and the model can call searchHelpCenter itself.</li>
 * </ul>
 */
public final class KnowledgeQuestionDetector {

    public enum Route { NONE, MAYBE, KNOWLEDGE, KNOWLEDGE_AND_LIVE }

    /** Topics of the app's rules and help pages. */
    private static final Pattern TOPIC = Pattern.compile(String.join("|",
            "\\bpolic(?:y|ies)\\b",
            "\\brefunds?\\b",
            "\\breturn (?:policy|window|period|rules?)\\b",
            "\\b(?:can|could|may) (?:i|we) (?:return|cancel|exchange|get a refund|reschedule)\\b",
            "\\bcancell?ations?\\b",
            "\\bdeliver(?:y|ies) (?:charges?|fees?|areas?|coverage|times?|hours|slots?|timings?|polic(?:y|ies)"
                    + "|process)\\b",
            "\\bfree deliver(?:y|ies)\\b|\\bdeliver(?:y|ies) (?:is |be )?free\\b",
            "\\bdo you deliver\\b|\\bwhere do you deliver\\b",
            "\\bminimum\\b(?:\\s+\\w+){0,2}\\s+order\\b|\\bmin order\\b",
            // (not the bare "cod": "do you have cod?" is about fish)
            "\\bpayment (?:methods?|options?|modes?)\\b|\\bcash on delivery\\b",
            "\\bregist(?:er|ration)\\b|\\bsign ?up\\b|\\bonboard(?:ing)?\\b",
            "\\b(?:forgot|reset|change) (?:my )?password\\b|\\bdelete my account\\b",
            "\\bbecome an? (?:vendor|seller|partner|delivery partner|restaurant partner)\\b",
            "\\bpayouts?\\b|\\bsettlements?\\b|\\bcommission\\b",
            "\\bguidelines?\\b|\\bterms and conditions\\b|\\bfaqs?\\b",
            "\\bstorage\\b|\\bshelf life\\b|\\bstay fresh\\b|\\bhow long (?:do|does|can|will) .{1,40} (?:last|keep)\\b",
            "\\bcustomer (?:care|support|service)\\b|\\bcontact (?:you|support|customer care)\\b"),
            Pattern.CASE_INSENSITIVE);

    /** How-to wording that may be about anything. */
    private static final Pattern HOW_TO = Pattern.compile(String.join("|",
            "\\bhow (?:do|can|should) (?:i|we)\\b|\\bhow to\\b|\\bwhat (?:happens|do i do|should i do) (?:if|when)\\b",
            "\\b(?:can|could|may) (?:i|we) (?:pay|change|edit|modify|use)\\b",
            // "What do the order statuses mean?", "How many addresses can I save?"
            "\\bwhat (?:does|do) .{1,40}\\bmean\\b|\\bhow many .{1,40}\\bcan (?:i|we)\\b"),
            Pattern.CASE_INSENSITIVE);

    /** The user's own data, or doing something: the assistant's tools are needed too. */
    private static final Pattern LIVE = Pattern.compile(String.join("|",
            "\\bmy (?:order|orders|cart|basket|payment|payments|refund|refunds|return|returns|complaint|complaints"
                    + "|delivery|deliveries)\\b",
            "\\b(?:this|that|current|last|latest|recent) order\\b",
            "\\border (?:number|no\\.?|#|id)\\b",
            "\\b[A-Z]{2,4}-(?=[A-Z0-9]*\\d)[A-Z0-9]{6,}\\b",
            "\\bwhere is\\b|\\btrack\\b|\\bstatus of\\b",
            "\\badd\\b|\\bremove\\b|\\bcheckout\\b|\\bplace (?:my |the |an? )?order\\b|\\bbuy\\b",
            "\\bfind\\b|\\bsearch\\b|\\bprice of\\b|\\bhow much (?:is|are|does)\\b|\\bin stock\\b"
                    + "|\\bavailab(?:le|ility)\\b|\\bshow me\\b",
            "\\b\\d+\\s?(?:kg|g|gm|grams?|l|ltr|litres?|ml|pcs|pieces?|packets?|dozen)\\b"),
            Pattern.CASE_INSENSITIVE);

    /** Rules whose numbers admins change in the app (platform settings): the answer needs the live values. */
    private static final Pattern STORE_RULES = Pattern.compile(String.join("|",
            "\\bminimum\\b(?:\\s+\\w+){0,2}\\s+order\\b|\\bmin order\\b",
            "\\bdeliver(?:y|ies) (?:charges?|fees?)\\b",
            "\\bfree deliver(?:y|ies)\\b|\\bdeliver(?:y|ies) (?:is |be )?free\\b"),
            Pattern.CASE_INSENSITIVE);

    private KnowledgeQuestionDetector() {
    }

    /**
     * The message asks about a rule whose amount admins set (minimum order, delivery fees, free delivery): the app
     * reads the values in force before the model, so the answer never depends on the model calling getStoreRules.
     */
    public static boolean aboutStoreRules(String message) {
        return message != null && STORE_RULES.matcher(message).find();
    }

    public static Route route(String message) {
        if (message == null || message.isBlank()) {
            return Route.NONE;
        }
        if (TOPIC.matcher(message).find()) {
            return LIVE.matcher(message).find() || STORE_RULES.matcher(message).find()
                    ? Route.KNOWLEDGE_AND_LIVE : Route.KNOWLEDGE;
        }
        return HOW_TO.matcher(message).find() ? Route.MAYBE : Route.NONE;
    }
}
