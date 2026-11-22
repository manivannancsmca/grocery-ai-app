package com.grocery.ai.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.math.BigDecimal;
import java.time.Duration;

/**
 * {@code grocery.ai.*} (contract §1–§4; env names in brackets, see application.yml).
 *
 * @param provider        model provider ({@code AI_PROVIDER}): ollama (default, local development), anthropic
 *                        (production: Claude) or scripted (local/docker only)
 * @param gatewayUrl      where tools and approval execution reach the business APIs ({@code GROCERY_AI_GATEWAY_URL})
 * @param toolTimeout     HTTP timeout of one tool call through the gateway (5 s)
 * @param modelTimeout    longest wait for the model's next token or answer ({@code AI_MODEL_TIMEOUT}, 120 s)
 * @param messagesPerHour chat messages per user per rolling hour ({@code AI_MESSAGES_PER_HOUR}, 30)
 * @param maxToolCalls    tool calls per user message; one more ends the turn with AI_TOOL_LIMIT (6)
 * @param memoryMessages  messages of the conversation the model sees, the current one included (20)
 * @param statusCacheTtl  how long a model availability probe is reused (30 s)
 * @param customerCare    business facts the payment and support assistants may tell customers
 * @param shoppingCommands simple shopping commands ("add 2 basmati rice", "show my cart", "place my order") are handled
 *                        in code before the model ({@code ShoppingCommands}; {@code AI_SHOPPING_COMMANDS}, true)
 * @param mcp             the MCP pilot: tools reached on the domain services' MCP servers ({@link Mcp})
 */
@ConfigurationProperties(prefix = "grocery.ai")
public record AiProperties(
        @DefaultValue("ollama") Provider provider,
        @DefaultValue("http://localhost:8080") String gatewayUrl,
        @DefaultValue("5s") Duration toolTimeout,
        @DefaultValue("120s") Duration modelTimeout,
        @DefaultValue("30") int messagesPerHour,
        @DefaultValue("6") int maxToolCalls,
        @DefaultValue("20") int memoryMessages,
        @DefaultValue("30s") Duration statusCacheTtl,
        @DefaultValue Ollama ollama,
        @DefaultValue Anthropic anthropic,
        @DefaultValue Scripted scripted,
        @DefaultValue Approvals approvals,
        @DefaultValue Voice voice,
        @DefaultValue CustomerCare customerCare,
        @DefaultValue("true") boolean shoppingCommands,
        @DefaultValue Mcp mcp) {

    /**
     * The MCP pilot (2026-11-20; DEVELOPMENT_PLAN "REST vs MCP"): tools a domain service publishes on its own MCP
     * server are reached as an MCP client (stateless Streamable HTTP through the gateway, the user's own token on
     * every call), still through ToolGuard and its audit. Pilot: {@code searchProducts} (catalog-query-service)
     * and {@code getOrderStatus} (order-service).
     *
     * @param enabled     use the MCP servers ({@code AI_MCP_ENABLED}, true); false = the REST APIs, as before. A server
     *                    that cannot be reached falls back to the REST API for that call (audited as REST)
     * @param catalogPath catalog-query-service's MCP endpoint behind the gateway
     * @param ordersPath  order-service's MCP endpoint behind the gateway
     */
    public record Mcp(@DefaultValue("true") boolean enabled,
                      @DefaultValue("/api/mcp/catalog") String catalogPath,
                      @DefaultValue("/api/mcp/orders") String ordersPath) {
    }

    /**
     * {@code grocery.ai.customer-care.*}: facts about payments and complaints the payment and support assistants may
     * tell customers. They are configuration (not prompt text) so operators change them in one place.
     *
     * @param refundTimeline how long banks usually take to show a refund, e.g. "5–7 working days" (Razorpay's normal
     *                       refunds); empty = the assistants say nothing about timing ({@code AI_REFUND_TIMELINE})
     */
    public record CustomerCare(@DefaultValue("5–7 working days") String refundTimeline) {
    }

    /**
     * Who turns speech into text and text into speech for the assistants.
     * <ul>
     *   <li>{@code SERVER}: this service, through an OpenAI-compatible audio API ({@code /audio/transcriptions},
     *       {@code /audio/speech}): OpenAI itself, or a self-hosted server such as speaches (compose profile
     *       {@code voice}). The audio never leaves infrastructure the operator chose.</li>
     *   <li>{@code BROWSER}: the web app uses the browser's own speech recognition and synthesis (no server setup;
     *       some browsers send the audio to their vendor's speech service).</li>
     *   <li>{@code OFF}: no voice.</li>
     * </ul>
     */
    public enum VoiceMode { SERVER, BROWSER, OFF }

    /**
     * {@code grocery.ai.voice.*}.
     *
     * @param mode                SERVER, BROWSER (default) or OFF ({@code AI_VOICE_MODE})
     * @param baseUrl             the OpenAI-compatible API including {@code /v1}, e.g. {@code https://api.openai.com/v1}
     *                            or {@code http://localhost:8000/v1} ({@code AI_VOICE_BASE_URL}); required for SERVER
     * @param apiKey              sent as {@code Authorization: Bearer} when set ({@code AI_VOICE_API_KEY})
     * @param transcriptionModel  speech-to-text model ({@code AI_VOICE_TRANSCRIPTION_MODEL})
     * @param speechModel         text-to-speech model ({@code AI_VOICE_SPEECH_MODEL})
     * @param speechVoice         text-to-speech voice ({@code AI_VOICE_SPEECH_VOICE})
     * @param language            default spoken language (ISO 639-1, e.g. {@code en}); empty = detected
     *                            ({@code AI_VOICE_LANGUAGE})
     * @param timeout             longest wait for one transcription or speech answer ({@code AI_VOICE_TIMEOUT})
     * @param maxAudioBytes       largest recording accepted (10 MB; 60 s of compressed speech is far smaller)
     * @param maxRecordingSeconds longest recording the web app makes (told to it by {@code GET /api/ai/voice/status})
     * @param maxSpeechCharacters longest text read aloud at once
     * @param requestsPerHour     voice calls per user per hour on one replica (cost guard; {@code AI_VOICE_PER_HOUR})
     */
    public record Voice(@DefaultValue("BROWSER") VoiceMode mode,
                        @DefaultValue("") String baseUrl,
                        @DefaultValue("") String apiKey,
                        @DefaultValue("whisper-1") String transcriptionModel,
                        @DefaultValue("tts-1") String speechModel,
                        @DefaultValue("alloy") String speechVoice,
                        @DefaultValue("") String language,
                        @DefaultValue("30s") Duration timeout,
                        @DefaultValue("10485760") long maxAudioBytes,
                        @DefaultValue("60") int maxRecordingSeconds,
                        @DefaultValue("4000") int maxSpeechCharacters,
                        @DefaultValue("120") int requestsPerHour) {
    }

    /** The model providers (contract §1). */
    public enum Provider {
        OLLAMA, ANTHROPIC, SCRIPTED;

        public String id() {
            return name().toLowerCase();
        }
    }

    /**
     * @param baseUrl       Ollama's API ({@code OLLAMA_BASE_URL})
     * @param model         the chat model ({@code OLLAMA_MODEL}, qwen3:4b-instruct — a non-thinking model)
     * @param contextTokens the model's context window, sent as {@code num_ctx} ({@code AI_OLLAMA_CONTEXT_TOKENS},
     *                      8192). The shopping assistant's prompt and tools alone take about 3,100 tokens: with
     *                      Ollama's default of 4096 one search plus one cart change overflowed it, and Ollama then
     *                      dropped the start of the prompt (the rules and tools). The chat also fits the conversation
     *                      history into this window ({@code ChatService}). Costs about 0.6 GB more memory in Ollama.
     * @param warmUp        load the model and read these assistants' rules and tools before anyone asks
     *                      ({@code ModelWarmUp}; {@code AI_WARM_UP}, true): when the service starts, and when the
     *                      assistant panel opens while the model is not loaded (Ollama unloads it after an hour idle)
     * @param warmUpAgents  the assistants whose starts are read ({@code AI_WARM_UP_AGENTS}, {@code all}: every
     *                      assistant, the customers' first; or ids such as {@code shopping,admin-bi}). One start per
     *                      different set of tools an assistant's roles are offered; each costs up to ~70 s of CPU
     *                      once and ~0.3–0.5 GB of Ollama's RAM prompt cache while the model stays loaded
     * @param warmUpQuiet   how long no chat turn may have used the model before the next warm-up read (30 s): Ollama
     *                      answers one request at a time, and a follow-up question should not wait for a read
     */
    public record Ollama(@DefaultValue("http://localhost:11434") String baseUrl,
                         @DefaultValue("qwen3:4b-instruct") String model,
                         @DefaultValue("8192") int contextTokens,
                         @DefaultValue("true") boolean warmUp,
                         @DefaultValue("all") java.util.List<String> warmUpAgents,
                         @DefaultValue("30s") Duration warmUpQuiet) {
    }

    /**
     * The production model (2026-11-20): Claude through Anthropic's Messages API. The assistants' rules and tool
     * definitions are the same for every user, so they are cached on Anthropic's side (Spring AI's SYSTEM_AND_TOOLS
     * strategy with one system block per system message: the rules are cached, this user's context line after them is
     * not; see {@code ChatService.prompt}).
     *
     * @param apiKey        the API key ({@code ANTHROPIC_API_KEY}; AWS: Secrets Manager
     *                      {@code grocery/<env>/ai-orchestrator-service/anthropic}); empty = the model is unavailable
     * @param baseUrl       the API ({@code ANTHROPIC_BASE_URL}, https://api.anthropic.com)
     * @param model         the Claude model ({@code ANTHROPIC_MODEL}, claude-opus-5-5; claude-sonnet-5-5 or
     *                      claude-haiku-5-5 answer faster and cost less)
     * @param maxTokens     longest answer in tokens ({@code AI_ANTHROPIC_MAX_TOKENS}, 2048)
     * @param contextTokens the part of the model's context window the chat fills with history ({@code ChatService});
     *                      the window itself is far larger, and memoryMessages limits the history first
     * @param maxRetries    retries of a refused request (429 rate limit, 529 overloaded, 5xx) before the chat answers
     *                      AI_UNAVAILABLE ({@code AI_ANTHROPIC_MAX_RETRIES}, 1)
     */
    public record Anthropic(@DefaultValue("") String apiKey,
                            @DefaultValue("https://api.anthropic.com") String baseUrl,
                            @DefaultValue("claude-opus-5-5") String model,
                            @DefaultValue("2048") int maxTokens,
                            @DefaultValue("100000") int contextTokens,
                            @DefaultValue("1") int maxRetries) {

        /** The key is set (not empty, not the Secrets Manager placeholder). */
        public boolean configured() {
            return apiKey != null && !apiKey.isBlank() && !"REPLACE_ME".equals(apiKey);
        }
    }

    /**
     * @param allowAnyProfile tests only: allow the scripted model outside the local/docker profiles
     */
    public record Scripted(@DefaultValue("false") boolean allowAnyProfile) {
    }

    /**
     * @param ttlCustomer          expiry of self-decided requests: a customer's (PLACE_ORDER, CANCEL_ORDER,
     *                             REQUEST_RETURN) and a store owner's own changes ({@code AI_APPROVAL_TTL_CUSTOMER})
     * @param ttlAdmin             expiry of requests decided by roles: admin actions and REASSIGN_DELIVERY
     *                             ({@code AI_APPROVAL_TTL_ADMIN})
     * @param fourEyesRefundAbove  refunds above this amount need a second admin ({@code AI_FOUR_EYES_REFUND_ABOVE})
     * @param expiryInterval       how often overdue requests are expired ({@code AI_APPROVAL_EXPIRY_INTERVAL})
     * @param executingTimeout     an EXECUTING request older than this failed with EXECUTION_INTERRUPTED (2 min)
     * @param batchSize            rows per poller batch
     * @param pollersEnabled       run the expiry / recovery poller on a schedule (tests run it themselves)
     * @param signingKey           RSA private key, PKCS#8 PEM ({@code AI_APPROVAL_SIGNING_KEY}); empty = generated
     * @param tokenTtl             lifetime of an X-Approval-Token (60 s)
     * @param issuer               the token's {@code iss}
     * @param executionTimeout     HTTP timeout of the approved call through the gateway
     */
    public record Approvals(@DefaultValue("15m") Duration ttlCustomer,
                            @DefaultValue("24h") Duration ttlAdmin,
                            @DefaultValue("2000.00") BigDecimal fourEyesRefundAbove,
                            @DefaultValue("30s") Duration expiryInterval,
                            @DefaultValue("2m") Duration executingTimeout,
                            @DefaultValue("50") int batchSize,
                            @DefaultValue("true") boolean pollersEnabled,
                            @DefaultValue("") String signingKey,
                            @DefaultValue("60s") Duration tokenTtl,
                            @DefaultValue("ai-orchestrator-service") String issuer,
                            @DefaultValue("10s") Duration executionTimeout) {
    }

    /** The model name reported by {@code GET /api/ai/status} and the monitoring summary. */
    public String modelName() {
        return switch (provider) {
            case SCRIPTED -> "scripted";
            case ANTHROPIC -> anthropic.model();
            case OLLAMA -> ollama.model();
        };
    }

    /**
     * False for Anthropic without an API key: the chat and the insights then answer without calling it (no customer
     * data leaves with a key that cannot work), as if the model were down.
     */
    public boolean modelConfigured() {
        return provider != Provider.ANTHROPIC || anthropic.configured();
    }

    /** The tokens the chat may fill with the rules, tools, history and the new message ({@code ChatService}). */
    public int contextTokens() {
        return provider == Provider.ANTHROPIC ? anthropic.contextTokens() : ollama.contextTokens();
    }
}
