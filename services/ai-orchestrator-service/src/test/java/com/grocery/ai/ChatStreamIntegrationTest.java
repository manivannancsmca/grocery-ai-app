package com.grocery.ai;

import com.grocery.ai.SseClient.Event;
import com.grocery.ai.SseClient.Result;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.web.client.ResourceAccessException;
import reactor.core.publisher.Flux;
import tools.jackson.databind.JsonNode;

import java.net.ConnectException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code POST /api/ai/agents/{agent}/chat} over real HTTP (contract §2): event order, tokens, done / error, the stored
 * transcript and the model's context; errors before the stream are ProblemDetails.
 */
class ChatStreamIntegrationTest extends AbstractIntegrationTest {

    @Test
    void aSearchStreamsConversationToolEventsTokensAndDone() throws Exception {
        UUID customer = UUID.randomUUID();
        String token = TOKENS.token(customer, "CUSTOMER");
        stubCart(Fixtures.defaultCart());
        GATEWAY.json("GET", "/api/search/products", 200, Fixtures.searchResults(List.of(
                Fixtures.productCard(Fixtures.RICE, "Smart Rice", "100.00", true),
                Fixtures.productCard(UUID.randomUUID(), "Basmati Rice", "180.00", false))));

        Result result = chat(token, "shopping", "Search rice");

        assertThat(result.status()).isEqualTo(200);
        assertThat(result.header("Content-Type")).startsWith("text/event-stream");
        assertThat(result.names().getFirst()).isEqualTo("conversation");
        assertThat(result.names()).containsSubsequence("conversation", "tool", "tool", "token", "done");
        assertThat(result.last().name()).isEqualTo("done");
        assertThat(result.named("token")).hasSizeGreaterThan(3);              // streamed in chunks
        assertThat(result.named("error")).isEmpty();

        Event conversation = result.first("conversation");
        assertThat(conversation.text("agent")).isEqualTo("shopping");
        UUID conversationId = UUID.fromString(conversation.text("conversationId"));

        List<Event> tools = result.named("tool");
        assertThat(tools).extracting(e -> e.text("status")).containsExactly("STARTED", "SUCCEEDED");
        assertThat(tools).extracting(e -> e.text("invocationId")).containsOnly(tools.getFirst().text("invocationId"));
        assertThat(tools.getLast().text("tool")).isEqualTo("searchProducts");
        assertThat(tools.getLast().text("label")).isEqualTo("Searched products");
        assertThat(tools.getLast().text("summary")).isEqualTo("Found 2 products for “rice”");
        // the page that shows the same results: a link under the answer (none while the tool runs)
        assertThat(tools.getFirst().data().get("links")).isEmpty();
        JsonNode links = tools.getLast().data().get("links");
        assertThat(links).hasSize(1);
        assertThat(links.get(0).get("label").asString()).isEqualTo("See all results for \"rice\"");
        assertThat(links.get(0).get("path").asString()).isEqualTo("/search?q=rice");

        assertThat(result.answer()).isEqualTo("I found: 1. Smart Rice (1 kg) – ₹100.00; 2. Basmati Rice (1 kg) – "
                + "₹180.00, out of stock.");
        Event done = result.last();
        assertThat(done.text("finishReason")).isEqualTo("STOP");
        UUID messageId = UUID.fromString(done.text("messageId"));

        // the tool called the search through the gateway with the customer's own token and the correlation id
        FakeGateway.Request search = GATEWAY.requests("GET", "/api/search/products").getFirst();
        assertThat(search.param("q")).isEqualTo("rice");
        assertThat(search.param("size")).isEqualTo("5");
        assertThat(search.header("Authorization")).isEqualTo("Bearer " + token);
        assertThat(search.header("X-Correlation-Id")).isEqualTo("test-corr-12345");

        // transcript: the user message and the answer, with its tool activity
        JsonNode view = body(mvc.perform(get("/api/ai/conversations/{id}", conversationId)
                .with(as(customer, "CUSTOMER"))).andExpect(status().isOk()));
        assertThat(view.get("title").asString()).isEqualTo("Search rice");
        JsonNode messages = view.get("messages");
        assertThat(messages).hasSize(2);
        assertThat(messages.get(0).get("role").asString()).isEqualTo("USER");
        assertThat(messages.get(0).get("content").asString()).isEqualTo("Search rice");
        assertThat(messages.get(0).get("tools")).isEmpty();
        assertThat(messages.get(1).get("id").asString()).isEqualTo(messageId.toString());
        assertThat(messages.get(1).get("content").asString()).isEqualTo(result.answer());
        assertThat(messages.get(1).get("tools").get(0).get("status").asString()).isEqualTo("SUCCEEDED");
        assertThat(messages.get(1).get("tools").get(0).get("invocationId").asString())
                .isEqualTo(tools.getFirst().text("invocationId"));
        assertThat(messages.get(1).get("tools").get(0).get("links")).isEqualTo(links);     // the same link later
        assertThat(messages.get(1).get("approvals")).isEmpty();

        // audited
        List<Map<String, Object>> audit = auditRows(customer);
        assertThat(audit).hasSize(1);
        assertThat(audit.getFirst()).containsEntry("tool", "searchProducts").containsEntry("status", "SUCCEEDED")
                .containsEntry("http_status", 200).containsEntry("message_id", messageId.toString())
                .containsEntry("conversation_id", conversationId.toString());
        assertThat(json.readTree((String) audit.getFirst().get("arguments")).get("query").asString())
                .isEqualTo("rice");
        assertThat(json.readTree((String) audit.getFirst().get("page_links"))).isEqualTo(links);
    }

    @Test
    void aPriceQuestionSearchesTheWholeCatalogAndCountsEveryMatch() {
        UUID customer = UUID.randomUUID();
        String token = TOKENS.token(customer, "CUSTOMER");
        stubCart(Fixtures.defaultCart());
        GATEWAY.json("GET", "/api/search/products", 200, Fixtures.searchResults(List.of(
                Fixtures.productCard(UUID.randomUUID(), "Salt", "28.00", true),
                Fixtures.productCard(UUID.randomUUID(), "Poha", "45.00", true)), 21));

        Result result = chat(token, "shopping", "how many products below 50 rupees ?");

        assertThat(result.named("error")).isEmpty();
        // no text query (a query such as "products" would match nothing), the price bound, cheapest first
        FakeGateway.Request search = GATEWAY.requests("GET", "/api/search/products").getFirst();
        assertThat(search.param("q")).isNull();
        assertThat(search.param("maxPrice")).isEqualTo("50.00");
        assertThat(search.param("sort")).isEqualTo("PRICE_ASC");
        assertThat(result.named("tool").getLast().text("summary"))
                .isEqualTo("Found 21 products up to ₹50.00 (showing 2)");
        // the link opens the search page with the same filter, where all 21 are listed
        JsonNode link = result.named("tool").getLast().data().get("links").get(0);
        assertThat(link.get("label").asString()).isEqualTo("See all products up to ₹50.00");
        assertThat(link.get("path").asString()).isEqualTo("/search?maxPrice=50&sort=PRICE_ASC");
        // the count is every match, not the products listed
        assertThat(result.answer()).isEqualTo("I found 21 products up to ₹50.00. The first 2: 1. Salt (1 kg) – "
                + "₹28.00; 2. Poha (1 kg) – ₹45.00.");
        // the model is told the bound is inclusive (so it does not say "below ₹50" for products at ₹50)
        Message toolResult = model.prompts().getLast().getInstructions().getLast();
        assertThat(toolResult).isInstanceOf(ToolResponseMessage.class);
        assertThat(((ToolResponseMessage) toolResult).getResponses().getFirst().responseData())
                .contains("\"priceFilter\":\"₹50.00 or less (₹50.00 included)\"").contains("\"totalFound\":21");
    }

    @Test
    void anAnswerClaimingAChangeWithoutAToolCallIsAskedAgainAndReplaced() throws Exception {
        // The local model's live failure: "I've added …" with the cart untouched. Asked again, it uses its tool.
        UUID customer = UUID.randomUUID();
        String token = TOKENS.token(customer, "CUSTOMER");
        stubCart(Fixtures.defaultCart());
        model.use(new TestModels.Fake() {
            @Override
            public Flux<ChatResponse> stream(Prompt prompt) {
                if (prompt.getInstructions().getLast() instanceof ToolResponseMessage) {
                    return Flux.just(TestModels.response("Your cart has 3 items from Fresh Mart.", "STOP"));
                }
                if (TestModels.lastUserText(prompt).startsWith("[From the app, not the customer]")) {
                    AssistantMessage call = AssistantMessage.builder().content("").toolCalls(List.of(
                            new AssistantMessage.ToolCall("call_1", "function", "getCart", "{}"))).build();
                    return Flux.just(new ChatResponse(List.of(new Generation(call))));
                }
                return Flux.just(TestModels.response("I've added 2 packets of rice to your cart.", "STOP"));
            }
        });

        Result result = chat(token, "shopping", "add 2 packets of rice");

        assertThat(result.names()).containsSubsequence("conversation", "token", "retry", "tool", "tool", "token",
                "done");
        assertThat(result.first("retry").text("code")).isEqualTo("ACTION");
        assertThat(result.first("retry").text("message")).isEqualTo("Checking that with the app…");
        assertThat(toolEvents(result)).containsExactly("getCart:STARTED", "getCart:SUCCEEDED");
        assertThat(result.answer()).isEqualTo("Your cart has 3 items from Fresh Mart.");
        assertThat(result.named("notice")).isEmpty();
        // the model saw its own answer and was told why it is not backed, with its tools by name
        List<Message> again = model.prompts().get(1).getInstructions();
        assertThat(again.get(again.size() - 2).getText()).isEqualTo("I've added 2 packets of rice to your cart.");
        assertThat(TestModels.lastUserText(model.prompts().get(1)))
                .startsWith("[From the app, not the customer] Your reply above is not backed by the app: it says "
                        + "something was changed, but no tool changed anything in this turn.")
                .contains("your tools: ").contains("addToCart").contains("getCart");
        // the transcript keeps only the real answer; nothing was written
        UUID conversationId = UUID.fromString(result.first("conversation").text("conversationId"));
        JsonNode view = body(mvc.perform(get("/api/ai/conversations/{id}", conversationId)
                .with(as(customer, "CUSTOMER"))).andExpect(status().isOk()));
        assertThat(view.get("messages").get(1).get("content").asString())
                .isEqualTo("Your cart has 3 items from Fresh Mart.");
        assertThat(GATEWAY.requests("POST", ".*")).isEmpty();
    }

    @Test
    void anAnswerThatNeedsNoToolIsNotAskedAgain() {
        stubCart(Fixtures.defaultCart());
        model.use(TestModels.text("Happy to help with your shopping."));

        Result result = chat(TOKENS.token(UUID.randomUUID(), "CUSTOMER"), "shopping", "hi");

        assertThat(result.names()).doesNotContain("retry");
        assertThat(model.prompts()).hasSize(1);
        assertThat(result.answer()).isEqualTo("Happy to help with your shopping.");
    }

    /** A default address in Velachery, Chennai (with its location, as customer-service returns it). */
    private static final String HOME_IN_CHENNAI = "[{\"id\":\"" + UUID.randomUUID() + "\",\"label\":\"Home\","
            + "\"city\":\"Chennai\",\"isDefault\":true,\"latitude\":12.9782,\"longitude\":80.212}]";

    @Test
    void theProductSearchCoversOnlyTheStoresThatDeliverToTheDefaultAddress() {
        // Like the storefront: without this a Velachery customer was offered a Tambaram store's products.
        UUID customer = UUID.randomUUID();
        String token = TOKENS.token(customer, "CUSTOMER");
        stubCart(Fixtures.defaultCart());
        GATEWAY.json("GET", "/api/customers/me/addresses", 200, HOME_IN_CHENNAI);
        String nearest = UUID.randomUUID().toString();
        String next = UUID.randomUUID().toString();
        GATEWAY.json("GET", "/api/stores/nearby", 200, "{\"deliveryLimitKm\":15,\"content\":[{\"id\":\"" + nearest
                + "\",\"name\":\"Vijaya Nagar Fresh Mart\",\"distanceKm\":1.3,\"deliversHere\":true},{\"id\":\"" + next
                + "\",\"name\":\"Guindy Daily Fresh\",\"distanceKm\":4.5,\"deliversHere\":true}]}");
        GATEWAY.json("GET", "/api/search/products", 200, Fixtures.searchResults(List.of(
                Fixtures.productCard(Fixtures.RICE, "Smart Rice", "100.00", true))));

        Result result = chat(token, "shopping", "Search rice");

        assertThat(toolEvents(result)).containsExactly("searchProducts:STARTED", "searchProducts:SUCCEEDED");
        FakeGateway.Request nearby = GATEWAY.requests("GET", "/api/stores/nearby").getFirst();
        assertThat(nearby.param("lat")).isEqualTo("12.9782");
        assertThat(nearby.param("lng")).isEqualTo("80.212");
        // both stores, as the repeated parameter the search binds to a list
        FakeGateway.Request search = GATEWAY.requests("GET", "/api/search/products").getFirst();
        assertThat(search.query()).contains("vendorId=" + nearest + "&vendorId=" + next);
        assertThat(search.header("Authorization")).isEqualTo("Bearer " + token);
        // the model is told which stores it searched
        Message toolResult = model.prompts().getLast().getInstructions().getLast();
        assertThat(((ToolResponseMessage) toolResult).getResponses().getFirst().responseData())
                .contains("\"stores\":\"stores that deliver to Home (2)\"");
    }

    @Test
    void noStoreDeliveringToTheDefaultAddressIsSaidPlainly() {
        UUID customer = UUID.randomUUID();
        String token = TOKENS.token(customer, "CUSTOMER");
        stubCart(Fixtures.defaultCart());
        GATEWAY.json("GET", "/api/customers/me/addresses", 200, HOME_IN_CHENNAI);
        GATEWAY.json("GET", "/api/stores/nearby", 200, "{\"deliveryLimitKm\":15,\"content\":[]}");

        Result result = chat(token, "shopping", "Search rice");

        assertThat(toolEvents(result)).containsExactly("searchProducts:STARTED", "searchProducts:FAILED");
        assertThat(result.answer()).contains("No grocery store delivers to Home yet");
        assertThat(GATEWAY.requests("GET", "/api/search/products")).isEmpty();
    }

    @Test
    void withoutALocationOrWhenTheStoreListFailsEveryStoreIsSearched() {
        UUID customer = UUID.randomUUID();
        String token = TOKENS.token(customer, "CUSTOMER");
        stubCart(Fixtures.defaultCart());
        GATEWAY.json("GET", "/api/customers/me/addresses", 200, HOME_IN_CHENNAI);
        GATEWAY.on("GET", "/api/stores/nearby", request -> FakeGateway.Response.problem(503, "SERVICE_UNAVAILABLE",
                "vendor-service is down"));
        GATEWAY.json("GET", "/api/search/products", 200, Fixtures.searchResults(List.of(
                Fixtures.productCard(Fixtures.RICE, "Smart Rice", "100.00", true))));

        Result result = chat(token, "shopping", "Search rice");

        assertThat(toolEvents(result)).containsExactly("searchProducts:STARTED", "searchProducts:SUCCEEDED");
        assertThat(GATEWAY.requests("GET", "/api/search/products").getFirst().param("vendorId")).isNull();
    }

    @Test
    void anOrderStatusLinksToThatOrdersPage() {
        UUID customer = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        stubCart(Fixtures.defaultCart());
        GATEWAY.json("GET", "/api/orders", 200, Fixtures.page(List.of(
                Fixtures.map("id", orderId.toString(), "orderNumber", "GA-7K3F9Q2M"))));
        GATEWAY.json("GET", "/api/orders/" + orderId, 200, Fixtures.json(Fixtures.order(orderId, "GA-7K3F9Q2M",
                "OUT_FOR_DELIVERY", false, "225.00")));

        Result result = chat(TOKENS.token(customer, "CUSTOMER"), "shopping", "status of GA-7K3F9Q2M");

        assertThat(result.named("error")).isEmpty();
        Event tool = result.named("tool").getLast();
        assertThat(tool.text("status")).isEqualTo("SUCCEEDED");
        // the order page needs the order's id, which only the API's answer has (the model only knows the number)
        JsonNode link = tool.data().get("links").get(0);
        assertThat(link.get("label").asString()).isEqualTo("Track order GA-7K3F9Q2M");
        assertThat(link.get("path").asString()).isEqualTo("/orders/" + orderId);
    }

    @Test
    void theModelSeesTheServerContextAndTheConversationSoFar() {
        UUID customer = UUID.randomUUID();
        String token = TOKENS.token(customer, "CUSTOMER");
        stubCart(Fixtures.defaultCart());
        UUID home = UUID.randomUUID();
        GATEWAY.json("GET", "/api/customers/me/addresses", 200, "[{\"id\":\"" + UUID.randomUUID()
                + "\",\"label\":\"Office\",\"city\":\"Pune\",\"isDefault\":false},{\"id\":\"" + home
                + "\",\"label\":\"Home\",\"city\":\"Bengaluru\",\"isDefault\":true}]");

        Result first = chat(token, "shopping", "hello there");
        UUID conversationId = UUID.fromString(first.first("conversation").text("conversationId"));
        assertThat(first.answer()).startsWith("I'm the scripted assistant");
        Result second = chat(token, "shopping", "what can you do", conversationId);
        assertThat(second.first("conversation").text("conversationId")).isEqualTo(conversationId.toString());

        Prompt prompt = model.prompts().getLast();
        List<Message> messages = prompt.getInstructions();
        assertThat(messages.getFirst()).isInstanceOf(SystemMessage.class);
        String system = messages.getFirst().getText();
        assertThat(system).contains("shopping assistant").contains("untrusted data");
        // the customer's pages the model may link to, and only those
        assertThat(system).contains("Use only these links, exactly as written: [My cart](/cart), [Checkout](/checkout)")
                .contains("Never invent other links").doesNotContain("/admin/");
        // the rules are the same for every customer (Ollama reuses them and the tools); this customer's facts come
        // as a system message of their own, right before the new message
        assertThat(system).doesNotContain("Context (from the server").doesNotContain("Asha");
        assertThat(messages.get(messages.size() - 2)).isInstanceOf(SystemMessage.class);
        String contextLine = messages.get(messages.size() - 2).getText();
        assertThat(contextLine).startsWith("Context (from the server, trustworthy)")
                .contains("user role CUSTOMER").contains("first name Asha")
                .contains("cart: 3 items from Fresh Mart, subtotal ₹260.00").contains("(India)")
                .contains("default delivery address: Home, Bengaluru (id " + home + "; leave addressId out to use it)");
        assertThat(messages.subList(1, messages.size())).extracting(Message::getText)
                .containsExactly("hello there", first.answer(), contextLine, "what can you do");
        // the first turn's prompt started with the same rules
        assertThat(model.prompts().get(model.prompts().size() - 2).getInstructions().getFirst().getText())
                .isEqualTo(system);
    }

    @Test
    void theNextTurnRemembersTheIdsTheToolsReturnedButTheTranscriptStaysPlain() throws Exception {
        UUID customer = UUID.randomUUID();
        String token = TOKENS.token(customer, "CUSTOMER");
        stubCart(Fixtures.defaultCart());
        GATEWAY.json("GET", "/api/search/products", 200, Fixtures.searchResults(List.of(
                Fixtures.productCard(Fixtures.RICE, "Smart Rice", "100.00", true))));

        Result first = chat(token, "shopping", "Search rice");
        UUID conversationId = UUID.fromString(first.first("conversation").text("conversationId"));
        chat(token, "shopping", "what can you do", conversationId);

        // the model's memory: the answer, then the compact search result with the product id (for "add the first one")
        // as a note from the app, so the model never takes the block for part of its own answers and copies it
        List<Message> messages = model.prompts().getLast().getInstructions();
        assertThat(messages.get(1).getText()).isEqualTo("Search rice");
        assertThat(messages.get(2)).isInstanceOf(AssistantMessage.class);
        assertThat(messages.get(2).getText()).isEqualTo(first.answer());
        assertThat(messages.get(3)).isInstanceOf(UserMessage.class);
        assertThat(messages.get(3).getText()).startsWith("[From the app, not the customer] What the tools returned")
                .contains("searchProducts → ").contains(Fixtures.RICE.toString());
        assertThat(messages.get(4)).isInstanceOf(SystemMessage.class);           // this turn's context line
        assertThat(messages.get(5).getText()).isEqualTo("what can you do");

        // what the user sees is only the answer
        JsonNode view = body(mvc.perform(get("/api/ai/conversations/{id}", conversationId)
                .with(as(customer, "CUSTOMER"))).andExpect(status().isOk()));
        assertThat(view.get("messages").get(1).get("content").asString()).isEqualTo(first.answer());
    }

    @Test
    void anUnreachableModelEndsTheStreamWithAiUnavailableAndKeepsTheUserMessage() throws Exception {
        UUID customer = UUID.randomUUID();
        stubCart(Fixtures.defaultCart());
        model.use(TestModels.failing(new ResourceAccessException("I/O error on POST request for "
                + "\"http://ollama:11434/api/chat\": Connection refused", new ConnectException("Connection refused"))));

        Result result = chat(TOKENS.token(customer, "CUSTOMER"), "shopping", "show my cart");

        assertThat(result.status()).isEqualTo(200);
        assertThat(result.names()).containsExactly("conversation", "error");
        assertThat(result.last().text("code")).isEqualTo("AI_UNAVAILABLE");
        assertThat(result.last().text("message"))
                .isEqualTo("The assistant is not available right now. Everything else works as usual.");
        UUID conversationId = UUID.fromString(result.first("conversation").text("conversationId"));
        JsonNode view = body(mvc.perform(get("/api/ai/conversations/{id}", conversationId)
                .with(as(customer, "CUSTOMER"))).andExpect(status().isOk()));
        assertThat(view.get("messages")).hasSize(1);
        assertThat(view.get("messages").get(0).get("content").asString()).isEqualTo("show my cart");
        assertThat(jdbc().sql("SELECT error_code FROM ai_message WHERE conversation_id = UUID_TO_BIN(?)")
                .param(conversationId.toString()).query(String.class).single()).isEqualTo("AI_UNAVAILABLE");
    }

    @Test
    void aModelThatNeverAnswersEndsWithAiTimeout() {
        UUID customer = UUID.randomUUID();
        stubCart(Fixtures.defaultCart());
        model.use(TestModels.silent());

        Result result = chat(TOKENS.token(customer, "CUSTOMER"), "shopping", "show my cart");

        assertThat(result.names()).containsExactly("conversation", "error");
        assertThat(result.last().text("code")).isEqualTo("AI_TIMEOUT");
    }

    @Test
    void anUnexpectedModelFailureIsAiError() {
        UUID customer = UUID.randomUUID();
        stubCart(Fixtures.defaultCart());
        model.use(TestModels.failing(new IllegalStateException("model bug")));

        Result result = chat(TOKENS.token(customer, "CUSTOMER"), "shopping", "show my cart");

        assertThat(result.last().name()).isEqualTo("error");
        assertThat(result.last().text("code")).isEqualTo("AI_ERROR");
    }

    @Test
    void tokensAreFlushedAsTheModelProducesThem() throws Exception {
        UUID customer = UUID.randomUUID();
        stubCart(Fixtures.defaultCart());
        CountDownLatch firstTokenSeen = new CountDownLatch(1);
        AtomicLong waitedMs = new AtomicLong(-1);
        // the second chunk is produced only after the client has received the first one
        model.use(TestModels.gated(List.of("Hello ", "there."), index -> {
            if (index == 1) {
                long started = System.nanoTime();
                try {
                    boolean seen = firstTokenSeen.await(5, TimeUnit.SECONDS);
                    waitedMs.set(seen ? (System.nanoTime() - started) / 1_000_000 : -2);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }));

        Result result = SseClient.post(port, "/api/ai/agents/shopping/chat", TOKENS.token(customer, "CUSTOMER"),
                "{\"message\":\"hi\"}", "text/event-stream", event -> {
                    if (event.name().equals("token")) {
                        firstTokenSeen.countDown();
                    }
                });

        assertThat(result.answer()).isEqualTo("Hello there.");
        assertThat(waitedMs.get()).as("the client saw the first token before the model produced the second")
                .isGreaterThanOrEqualTo(0);
        assertThat(result.last().name()).isEqualTo("done");
    }

    @Test
    void errorsBeforeTheStreamAreProblemDetails() throws Exception {
        UUID customer = UUID.randomUUID();
        String token = TOKENS.token(customer, "CUSTOMER");

        Result unknown = chat(token, "vendor-ops", "hi");
        assertThat(unknown.status()).isEqualTo(404);
        assertThat(unknown.problem().get("errorCode").asString()).isEqualTo("AGENT_NOT_FOUND");
        assertThat(unknown.header("Content-Type")).startsWith("application/problem+json");

        Result blank = chat(token, "shopping", "   ");
        assertThat(blank.status()).isEqualTo(400);
        assertThat(blank.problem().get("errorCode").asString()).isEqualTo("VALIDATION_FAILED");
        Result tooLong = chat(token, "shopping", "x".repeat(2001));
        assertThat(tooLong.status()).isEqualTo(400);

        Result anonymous = chat(null, "shopping", "hi");
        assertThat(anonymous.status()).isEqualTo(401);

        // another user's conversation and another agent's conversation are "not found"
        stubCart(Fixtures.defaultCart());
        Result mine = chat(token, "shopping", "hello");
        UUID conversationId = UUID.fromString(mine.first("conversation").text("conversationId"));
        Result stranger = chat(TOKENS.token(UUID.randomUUID(), "CUSTOMER"), "shopping", "hello", conversationId);
        assertThat(stranger.status()).isEqualTo(404);
        assertThat(stranger.problem().get("errorCode").asString()).isEqualTo("CONVERSATION_NOT_FOUND");
        Result otherAgent = chat(TOKENS.token(customer, "CUSTOMER", "SUPPORT"), "admin-bi", "stats", conversationId);
        assertThat(otherAgent.status()).isEqualTo(404);
        assertThat(otherAgent.problem().get("errorCode").asString()).isEqualTo("CONVERSATION_NOT_FOUND");
    }

    @Test
    void aCustomerUsingTheAdminAssistantIsForbiddenAndAudited() {
        UUID customer = UUID.randomUUID();

        Result result = chat(TOKENS.token(customer, "CUSTOMER"), "admin-bi", "stats");

        assertThat(result.status()).isEqualTo(403);
        assertThat(result.problem().get("errorCode").asString()).isEqualTo("AGENT_NOT_ALLOWED");
        List<Map<String, Object>> audit = auditRows(customer);
        assertThat(audit).hasSize(1);
        assertThat(audit.getFirst()).containsEntry("tool", null).containsEntry("status", "BLOCKED")
                .containsEntry("reason", "AGENT_NOT_ALLOWED").containsEntry("agent", "admin-bi");
    }

    @Test
    void theHourlyMessageLimitAnswers429WithRetryAfter() {
        UUID customer = UUID.randomUUID();
        UUID conversation = UUID.randomUUID();
        jdbc().sql("INSERT INTO conversation (id, user_id, agent, title, created_at, updated_at) VALUES "
                        + "(UUID_TO_BIN(?), UUID_TO_BIN(?), 'shopping', 'old', UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))")
                .param(conversation.toString()).param(customer.toString()).update();
        for (int i = 0; i < 30; i++) {
            jdbc().sql("INSERT INTO ai_message (id, conversation_id, user_id, role, content, created_at) VALUES "
                            + "(UUID_TO_BIN(UUID()), UUID_TO_BIN(?), UUID_TO_BIN(?), 'USER', 'hi', "
                            + "UTC_TIMESTAMP(6) - INTERVAL ? MINUTE)")
                    .param(conversation.toString()).param(customer.toString()).param(50 - i).update();
        }

        Result result = chat(TOKENS.token(customer, "CUSTOMER"), "shopping", "one more");

        assertThat(result.status()).isEqualTo(429);
        assertThat(result.problem().get("errorCode").asString()).isEqualTo("AI_RATE_LIMITED");
        long retryAfter = Long.parseLong(result.header("Retry-After"));
        assertThat(retryAfter).isBetween(500L, 660L);                     // the oldest message leaves in ~10 min
    }

    @Test
    void conversationsAreListedNewestFirstAndCanBeDeleted() throws Exception {
        UUID customer = UUID.randomUUID();
        String token = TOKENS.token(customer, "CUSTOMER");
        stubCart(Fixtures.defaultCart());
        UUID first = UUID.fromString(chat(token, "shopping", "first chat").first("conversation").text("conversationId"));
        UUID second = UUID.fromString(chat(token, "shopping", "second chat").first("conversation")
                .text("conversationId"));

        JsonNode page = body(mvc.perform(get("/api/ai/conversations").param("agent", "shopping")
                .with(as(customer, "CUSTOMER"))).andExpect(status().isOk()));
        assertThat(page.get("totalElements").asInt()).isEqualTo(2);
        assertThat(page.get("content").get(0).get("id").asString()).isEqualTo(second.toString());
        assertThat(page.get("content").get(1).get("title").asString()).isEqualTo("first chat");
        assertThat(body(mvc.perform(get("/api/ai/conversations").param("agent", "admin-bi")
                .with(as(customer, "CUSTOMER")))).get("totalElements").asInt()).isZero();

        mvc.perform(delete("/api/ai/conversations/{id}", first).with(as(UUID.randomUUID(), "CUSTOMER")))
                .andExpect(status().isNotFound());
        mvc.perform(delete("/api/ai/conversations/{id}", first).with(as(customer, "CUSTOMER")))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/ai/conversations/{id}", first).with(as(customer, "CUSTOMER")))
                .andExpect(status().isNotFound());
        assertThat(jdbc().sql("SELECT COUNT(*) FROM SPRING_AI_CHAT_MEMORY WHERE conversation_id = ?")
                .param(first.toString()).query(Long.class).single()).isZero();
        assertThat(jdbc().sql("SELECT COUNT(*) FROM SPRING_AI_CHAT_MEMORY WHERE conversation_id = ?")
                .param(second.toString()).query(Long.class).single()).isEqualTo(2L);
    }
}
