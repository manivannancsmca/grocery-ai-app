package com.grocery.ai;

import com.grocery.ai.agent.Agents;
import com.grocery.ai.config.AiProperties;
import com.grocery.ai.model.ModelActivity;
import com.grocery.ai.model.ModelWarmUp;
import com.grocery.ai.tool.ToolGuard;
import com.grocery.ai.tool.ToolSpec;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.task.SyncTaskExecutor;
import org.springframework.core.task.support.TaskExecutorAdapter;
import org.springframework.web.client.RestClient;
import reactor.core.publisher.Flux;
import tools.jackson.databind.JsonNode;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The local model's warm-up (2026-11-21): a cold first question paid for loading the model and reading the shopping
 * assistant's ~3,200-token start (~70 s on CPU) and could hit the 120 s model timeout. Since 2026-11-22 every
 * assistant's start is read, once per different set of tools its roles are offered, and the warm-up gives way to chats.
 * Ollama is played by the fake gateway; the model call goes to the test model, which records the prompt.
 */
class ModelWarmUpIntegrationTest extends AbstractIntegrationTest {

    private static final Duration QUIET = Duration.ofSeconds(30);

    @Autowired
    private AiProperties properties;
    @Autowired
    private Agents agents;
    @Autowired
    private ToolGuard guard;
    @Autowired
    private ChatClient chatClient;
    @Autowired
    private RestClient.Builder restClientBuilder;

    /** The service's warm-up of the shopping assistant only, with Ollama at the fake gateway. */
    private ModelWarmUp warmUp(AiProperties.Provider provider) {
        return warmUp(provider, List.of("shopping"), new ModelActivity(clock));
    }

    /** The service's warm-up with Ollama at the fake gateway, running its background work right away. */
    private ModelWarmUp warmUp(AiProperties.Provider provider, List<String> assistants, ModelActivity activity) {
        AiProperties p = properties;
        AiProperties local = new AiProperties(provider, p.gatewayUrl(), p.toolTimeout(), p.modelTimeout(),
                p.messagesPerHour(), p.maxToolCalls(), p.memoryMessages(), p.statusCacheTtl(),
                new AiProperties.Ollama(GATEWAY.baseUrl(), "qwen3:4b-instruct", 8192, true, assistants, QUIET),
                p.anthropic(), p.scripted(), p.approvals(), p.voice(), p.customerCare(), p.shoppingCommands(),
                p.mcp());
        return new ModelWarmUp(local, agents, guard, activity, chatClient,
                new TaskExecutorAdapter(new SyncTaskExecutor()), json, clock, restClientBuilder);
    }

    private static List<ToolDefinition> tools(Prompt prompt) {
        return ((ToolCallingChatOptions) prompt.getOptions()).getToolCallbacks().stream()
                .map(ToolCallback::getToolDefinition).toList();
    }

    private static String describe(ModelWarmUp.Start start) {
        return start.agent().id() + " " + start.roles();
    }

    @Test
    void theWarmUpLoadsTheModelThenReadsTheShoppingAssistantsStartExactlyAsACustomersTurnBegins() {
        GATEWAY.json("POST", "/api/chat", 200, "{\"done\":true,\"done_reason\":\"load\"}");

        warmUp(AiProperties.Provider.OLLAMA).warmUp();

        // 1. Ollama's "only load": no messages, the chat's context window (another one would load it again)
        assertThat(GATEWAY.requests()).singleElement().satisfies(load -> {
            assertThat(load.path()).isEqualTo("/api/chat");
            JsonNode body = load.json();
            assertThat(body.get("model").asString()).isEqualTo("qwen3:4b-instruct");
            assertThat(body.get("messages").size()).isZero();
            assertThat(body.at("/options/num_ctx").asInt()).isEqualTo(8192);
        });
        // 2. one model call with the shopping assistant's rules first; no tool ran (the load was the only HTTP call)
        assertThat(model.prompts()).hasSize(1);
        Prompt warm = model.prompts().getFirst();
        List<Message> messages = warm.getInstructions();
        assertThat(messages.getFirst()).isInstanceOf(SystemMessage.class);
        assertThat(messages.getFirst().getText()).isEqualTo(agents.find("shopping").orElseThrow().systemPrompt());
        assertThat(messages.getLast().getText()).isEqualTo("hi");

        // A customer's real turn starts the same: the same rules and exactly the same tool definitions, so the start
        // Ollama keeps from the warm-up is the one the customer's question reuses.
        stubCart(Fixtures.defaultCart());
        chat(TOKENS.token(UUID.randomUUID(), "CUSTOMER"), "shopping", "hello");
        Prompt real = model.prompts().getLast();
        assertThat(real.getInstructions().getFirst().getText()).isEqualTo(messages.getFirst().getText());
        assertThat(tools(warm)).isNotEmpty().isEqualTo(tools(real));
    }

    @Test
    void everyAssistantIsReadOncePerDifferentSetOfToolsItsRolesGetTheCallersAssistantsFirst() {
        GATEWAY.json("POST", "/api/chat", 200, "{\"done\":true}");
        GATEWAY.json("GET", "/api/ps", 200, "{\"models\":[]}");
        ModelWarmUp warmUp = warmUp(AiProperties.Provider.OLLAMA, List.of("all"), new ModelActivity(clock));

        // The default ("all"): the customers' assistants first. Roles offered the same tools share a start (super and
        // ops admins); a finance admin, a support agent and a delivery manager get fewer admin tools, and delivery
        // partners and managers different delivery tools, so each of those is read on its own.
        assertThat(warmUp.starts(Set.of())).extracting(ModelWarmUpIntegrationTest::describe).containsExactly(
                "shopping [CUSTOMER]", "payment [CUSTOMER]", "support [CUSTOMER]",
                "admin-bi [DELIVERY_MANAGER]", "admin-bi [FINANCE_ADMIN]", "admin-bi [OPS_ADMIN, SUPER_ADMIN]",
                "admin-bi [SUPPORT]", "vendor [VENDOR]", "restaurant [RESTAURANT_OWNER]",
                "delivery [DELIVERY_MANAGER]", "delivery [DELIVERY_PARTNER]");
        // whoever opens the assistant panel has their own assistants read first, in the same order otherwise
        assertThat(warmUp.starts(Set.of("DELIVERY_MANAGER"))).extracting(ModelWarmUpIntegrationTest::describe)
                .startsWith("admin-bi [DELIVERY_MANAGER]", "delivery [DELIVERY_MANAGER]", "shopping [CUSTOMER]");
        assertThat(warmUp.starts(Set.of("SUPER_ADMIN")).getFirst().roles()).contains("SUPER_ADMIN");

        // A vendor opens the panel while the model is not loaded: every start is read, the vendor's first.
        warmUp.warmIfCold(Set.of("VENDOR"));

        List<ModelWarmUp.Start> starts = warmUp.starts(Set.of("VENDOR"));
        assertThat(describe(starts.getFirst())).isEqualTo("vendor [VENDOR]");
        assertThat(GATEWAY.requests("POST", "/api/chat")).hasSize(1);          // loaded once
        assertThat(model.prompts()).hasSize(11);
        for (int i = 0; i < starts.size(); i++) {
            ModelWarmUp.Start start = starts.get(i);
            Prompt prompt = model.prompts().get(i);
            assertThat(prompt.getInstructions().getFirst().getText()).isEqualTo(start.agent().systemPrompt());
            // exactly the tools each of the start's roles is offered in a real turn (ToolGuard.callbacks)
            for (String role : start.roles()) {
                List<String> offered = start.agent().tools().stream().filter(tool -> tool.mayBeUsedBy(Set.of(role)))
                        .map(ToolSpec::name).toList();
                assertThat(tools(prompt)).extracting(ToolDefinition::name).as(describe(start))
                        .isNotEmpty().isEqualTo(offered);
            }
        }
        assertThat(GATEWAY.requests()).as("no tool ran").hasSize(2);           // the /api/ps check and the load
    }

    @Test
    void theWarmUpGivesWayToChatsUsingTheModelAndWaitsForAFollowUp() throws Exception {
        GATEWAY.json("POST", "/api/chat", 200, "{\"done\":true}");
        ModelActivity activity = new ModelActivity(clock);
        ModelWarmUp warmUp = warmUp(AiProperties.Provider.OLLAMA, List.of("shopping"), activity);
        ModelActivity.Use turn = activity.begin();     // a customer's turn: the model asked for a tool that is running

        Thread warming = Thread.ofVirtual().start(warmUp::warmUp);
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (GATEWAY.requests("POST", "/api/chat").isEmpty() && System.nanoTime() < deadline) {
            Thread.sleep(50);
        }
        assertThat(GATEWAY.requests("POST", "/api/chat")).as("the model loads meanwhile").hasSize(1);
        Thread.sleep(1_500);
        assertThat(model.prompts()).as("no read while the turn uses the model").isEmpty();

        turn.close();
        turn.close();                                  // closing twice counts once
        Thread.sleep(1_500);
        assertThat(model.prompts()).as("nor right after it: the customer may ask a follow-up").isEmpty();
        assertThat(activity.idleFor(QUIET)).isFalse();

        clock.advance(QUIET);
        warming.join(Duration.ofSeconds(10));
        assertThat(warming.isAlive()).isFalse();
        assertThat(model.prompts()).hasSize(1);
        assertThat(activity.idleFor(QUIET)).isTrue();
    }

    @Test
    void someoneWhoBeginsWaitingForTheModelStopsTheReadInProgressWhichIsReadAgainLater() throws Exception {
        GATEWAY.json("POST", "/api/chat", 200, "{\"done\":true}");
        AtomicInteger reads = new AtomicInteger();
        AtomicInteger stopped = new AtomicInteger();
        CountDownLatch reading = new CountDownLatch(1);
        model.use(new TestModels.Fake() {
            @Override
            public Flux<ChatResponse> stream(Prompt prompt) {
                if (reads.incrementAndGet() == 1) {        // the first read is a long one: it does not end by itself
                    return Flux.<ChatResponse>never().doOnSubscribe(subscription -> reading.countDown())
                            .doOnCancel(stopped::incrementAndGet);
                }
                return Flux.just(TestModels.response("ok", "STOP"));
            }
        });
        ModelActivity activity = new ModelActivity(clock);
        ModelWarmUp warmUp = warmUp(AiProperties.Provider.OLLAMA, List.of("shopping"), activity);

        Thread warming = Thread.ofVirtual().start(warmUp::warmUp);
        assertThat(reading.await(10, TimeUnit.SECONDS)).as("reading the shopping assistant's start").isTrue();

        // A customer asks something: the read is stopped right away (its connection closed: Ollama cancels it).
        ModelActivity.Use question = activity.begin();
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (stopped.get() == 0 && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        assertThat(stopped).hasValue(1);
        Thread.sleep(1_500);
        assertThat(model.prompts()).as("not read again while the customer waits").hasSize(1);

        // Once the answer is done and nothing used the model for a while, the same start is read again.
        question.close();
        clock.advance(QUIET);
        warming.join(Duration.ofSeconds(10));
        assertThat(warming.isAlive()).isFalse();
        assertThat(model.prompts()).hasSize(2);
        assertThat(model.prompts().get(1).getInstructions().getFirst().getText())
                .isEqualTo(agents.find("shopping").orElseThrow().systemPrompt());
        assertThat(GATEWAY.requests("POST", "/api/chat")).as("the model was loaded once, never stopped").hasSize(1);
    }

    @Test
    void theAssistantPanelsStatusCheckWarmsUpOnlyWhenTheModelIsNotLoaded() {
        GATEWAY.json("POST", "/api/chat", 200, "{\"done\":true}");
        GATEWAY.json("GET", "/api/ps", 200, "{\"models\":[{\"name\":\"qwen3:4b-instruct\",\"context_length\":8192}]}");
        ModelWarmUp warmUp = warmUp(AiProperties.Provider.OLLAMA);

        warmUp.warmIfCold(Set.of("CUSTOMER"));
        assertThat(GATEWAY.requests("POST", "/api/chat")).isEmpty();          // loaded: nothing to do

        // Ollama unloaded it (an hour idle): warmed again, but checked at most once a minute
        GATEWAY.json("GET", "/api/ps", 200, "{\"models\":[]}");
        warmUp.warmIfCold(Set.of("CUSTOMER"));
        assertThat(GATEWAY.requests("GET", "/api/ps")).hasSize(1);
        clock.advance(Duration.ofMinutes(1));
        warmUp.warmIfCold(Set.of("CUSTOMER"));
        assertThat(GATEWAY.requests("POST", "/api/chat")).hasSize(1);
        assertThat(model.prompts()).hasSize(1);

        // loaded with another context window counts as not loaded (the chat would load it again)
        GATEWAY.json("GET", "/api/ps", 200, "{\"models\":[{\"name\":\"qwen3:4b-instruct\",\"context_length\":4096}]}");
        assertThat(warmUp.loaded()).isFalse();
        // other providers never warm up
        warmUp(AiProperties.Provider.SCRIPTED).warmIfCold(Set.of());
        warmUp(AiProperties.Provider.ANTHROPIC).warmIfCold(Set.of());
        assertThat(GATEWAY.requests("GET", "/api/ps")).hasSize(3);
    }
}
