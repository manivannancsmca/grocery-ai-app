package com.grocery.ai.model;

import com.grocery.ai.agent.AgentDefinition;
import com.grocery.ai.agent.Agents;
import com.grocery.ai.config.AiProperties;
import com.grocery.ai.gateway.CallContext;
import com.grocery.ai.support.Json;
import com.grocery.ai.tool.ToolGuard;
import com.grocery.ai.tool.ToolSpec;
import com.grocery.ai.tool.ToolTurn;
import com.grocery.common.security.CurrentUser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Gets the local model ready before anyone asks (Ollama only; {@code grocery.ai.ollama.warm-up}). A cold first question
 * paid for loading the model AND for reading the assistant's rules and tool definitions (~3,200 tokens for the
 * shopping assistant, ~70 s at ~48 tokens/s on CPU) before its first token, close to {@code model-timeout} (120 s);
 * with too little memory it went over and the customer got "took too long to answer" (2026-11-21).
 * <ol>
 *   <li>Load the model with the chat's context window ({@code num_ctx}; another one would make Ollama load it again),
 *       through Ollama's "no messages = only load" request, with a long timeout: a load cut short by the chat's
 *       timeout is aborted by Ollama and starts again from nothing.</li>
 *   <li>For each {@link Start} of the {@code warm-up-agents} (every assistant by default since 2026-11-22; one start
 *       per different set of tools the assistant's roles are offered): one model call with its rules and those tool
 *       definitions, through the chat's own {@link ChatClient}, so the start of the prompt is the same as a real
 *       turn's ({@code ChatService.prompt}); one token answered, no tool is run. Ollama keeps the start it read last
 *       in its slot and the others in its RAM prompt cache (llama.cpp server, up to 8 GB), from which a turn of that
 *       assistant restores its start instead of reading it again.</li>
 * </ol>
 * When: once the service has started, and when the assistant panel asks {@code GET /api/ai/status} while the model is
 * not loaded (Ollama unloads it after {@code OLLAMA_KEEP_ALIVE}, an hour idle), so it loads while the user types; then
 * the starts of that user's assistants are read first. Ollama answers one request at a time, so the warm-up gives way
 * to whatever someone waits for ({@link ModelActivity}: chat turns, insight reports): it starts a read only once nothing
 * used the model for {@code warm-up-quiet}, and stops the read in progress as soon as such work begins (2026-11-22). It
 * reads through a stream whose connection it closes; Ollama then cancels the read, llama.cpp ends it after the batch it
 * is on (up to 1,024 tokens, ~20 s on CPU instead of the whole start's ~70 s) and keeps what it read, so reading that
 * start again later goes on from there. The model load itself is never stopped (the question needs it as well). One
 * warm-up at a time; failures are logged and change nothing (the chat works as before, only slower).
 */
@Component
public class ModelWarmUp {

    private static final Logger log = LoggerFactory.getLogger(ModelWarmUp.class);
    /** Loading a 2.5 GB model from disk into a busy machine: minutes, not the chat's 120 s. */
    private static final Duration LOAD_TIMEOUT = Duration.ofMinutes(10);
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);
    /** How often the status endpoint may check whether the model is still loaded. */
    static final Duration CHECK_INTERVAL = Duration.ofMinutes(1);
    /** Not a person: the warm-up only builds a role's tool definitions, it never calls a tool. */
    private static final UUID WARM_UP_USER = new UUID(0, 0);
    static final String QUESTION = "hi";
    /** {@code warm-up-agents} value for every assistant (the default), in {@link Agents#all()}'s order. */
    public static final String ALL = "all";
    private static final Duration POLL = Duration.ofSeconds(1);

    private final AiProperties properties;
    private final Agents agents;
    private final ToolGuard guard;
    private final ModelActivity activity;
    private final ChatClient chatClient;
    private final AsyncTaskExecutor executor;
    private final JsonMapper jsonMapper;
    private final Clock clock;
    private final RestClient ollama;
    private final AtomicBoolean running = new AtomicBoolean();
    private volatile Instant lastCheck = Instant.EPOCH;
    /** The read in progress and what its reader waits on; both null between reads. Guarded by {@code this}. */
    private Disposable reading;
    private CompletableFuture<Boolean> readDone;
    /** Someone began waiting for the model since the warm-up last gave way. Guarded by {@code this}. */
    private boolean someoneWaiting;

    /** How a read ended. */
    private enum Outcome { READ, STOPPED, FAILED }

    public ModelWarmUp(AiProperties properties, Agents agents, ToolGuard guard, ModelActivity activity,
                       ChatClient chatClient, AsyncTaskExecutor aiTaskExecutor, JsonMapper jsonMapper, Clock clock,
                       RestClient.Builder restClientBuilder) {
        this.properties = properties;
        this.agents = agents;
        this.guard = guard;
        this.activity = activity;
        this.chatClient = chatClient;
        this.executor = aiTaskExecutor;
        this.jsonMapper = jsonMapper;
        this.clock = clock;
        HttpClient httpClient = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT)
                .version(HttpClient.Version.HTTP_1_1).build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(LOAD_TIMEOUT);
        this.ollama = restClientBuilder.clone().requestFactory(factory).build();
        activity.onBegin(this::stopReading);
    }

    private boolean enabled() {
        return properties.provider() == AiProperties.Provider.OLLAMA && properties.ollama().warmUp();
    }

    /** Once the service is up (in the background: the service answers meanwhile). */
    @EventListener(ApplicationReadyEvent.class)
    public void onStarted() {
        if (enabled()) {
            executor.execute(this::warmUp);
        }
    }

    /**
     * The assistant panel opened ({@code GET /api/ai/status}): warm up again in the background if the model is no longer
     * loaded, the starts of the caller's roles first. At most one check a minute; returns at once.
     */
    public void warmIfCold(Set<String> callerRoles) {
        if (!enabled() || running.get()) {
            return;
        }
        Instant now = clock.instant();
        if (now.isBefore(lastCheck.plus(CHECK_INTERVAL))) {
            return;
        }
        lastCheck = now;
        executor.execute(() -> {
            if (!loaded()) {
                warmUp(callerRoles);
            }
        });
    }

    /** Loads the model, then reads every start in the configured order. One at a time; blocks until done. */
    public void warmUp() {
        warmUp(Set.of());
    }

    /**
     * Loads the model, then reads every start, those of {@code first} (roles) before the others, each once nothing
     * uses the model; a start whose read was stopped for someone is read again. One at a time; blocks until done.
     */
    public void warmUp(Set<String> first) {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        try {
            long started = System.nanoTime();
            if (!load()) {
                return;
            }
            log.info("AI warm-up: {} loaded in {} s", properties.ollama().model(), seconds(started));
            List<Start> starts = starts(first);
            for (Start start : starts) {
                Outcome outcome;
                do {
                    if (!giveWay()) {
                        return;
                    }
                    outcome = read(start);
                } while (outcome == Outcome.STOPPED);
            }
            log.info("AI warm-up: done, {} assistant starts read in {} s", starts.size(), seconds(started));
        } finally {
            running.set(false);
        }
    }

    /**
     * An assistant's start as some of its roles see it: its rules and the tool definitions those roles are offered,
     * the same for each of {@code roles} (SUPER_ADMIN and OPS_ADMIN; a FINANCE_ADMIN is offered fewer, a delivery
     * partner other ones than a delivery manager).
     */
    public record Start(AgentDefinition agent, Set<String> roles) {
    }

    /**
     * What a warm-up reads, in order: for each configured assistant, one start per different set of tools its roles are
     * offered ({@code ToolGuard.callbacks}); the starts of any of {@code first} before the others.
     */
    public List<Start> starts(Set<String> first) {
        List<Start> starts = new ArrayList<>();
        for (AgentDefinition agent : configuredAgents()) {
            Map<List<String>, Set<String>> rolesByTools = new LinkedHashMap<>();
            for (String role : new TreeSet<>(agent.roles())) {
                List<String> tools = agent.tools().stream().filter(tool -> tool.mayBeUsedBy(Set.of(role)))
                        .map(ToolSpec::name).toList();
                rolesByTools.computeIfAbsent(tools, ignored -> new TreeSet<>()).add(role);
            }
            rolesByTools.values().forEach(roles -> starts.add(new Start(agent, Collections.unmodifiableSet(roles))));
        }
        starts.sort(Comparator.comparing(start -> Collections.disjoint(start.roles(), first)));   // stable: false first
        return starts;
    }

    private List<AgentDefinition> configuredAgents() {
        List<String> ids = properties.ollama().warmUpAgents().stream().map(String::strip)
                .filter(id -> !id.isEmpty()).toList();
        if (ids.contains(ALL)) {
            return agents.all();
        }
        List<AgentDefinition> chosen = new ArrayList<>();
        for (String id : ids) {
            agents.find(id).ifPresentOrElse(chosen::add,
                    () -> log.warn("AI warm-up: no assistant called \"{}\" (grocery.ai.ollama.warm-up-agents)", id));
        }
        return chosen;
    }

    /**
     * Waits until nothing has used the model for {@code warm-up-quiet} (a follow-up question comes first); false when
     * interrupted (the service is stopping). From its return on, anyone who begins waiting stops the next read.
     */
    private boolean giveWay() {
        boolean waited = false;
        while (!Thread.currentThread().isInterrupted()) {
            synchronized (this) {                   // with stopReading: no one who begins is missed
                if (activity.idleFor(properties.ollama().warmUpQuiet())) {
                    someoneWaiting = false;
                    return true;
                }
            }
            if (!waited) {
                log.info("AI warm-up: waiting for the chats in progress");
                waited = true;
            }
            try {
                Thread.sleep(POLL);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        return false;
    }

    /**
     * Someone began waiting for the model ({@link ModelActivity#begin}, on their thread): stop the read in progress.
     * Closing its connection makes Ollama cancel it; llama.cpp ends after its current batch and keeps what it read.
     */
    private synchronized void stopReading() {
        someoneWaiting = true;
        if (reading != null) {
            reading.dispose();
            readDone.complete(false);
        }
    }

    /** Is the model loaded in Ollama with the chat's context window ({@code GET /api/ps})? False when unsure. */
    public boolean loaded() {
        try {
            String body = ollama.get().uri(baseUrl() + "/api/ps").retrieve().body(String.class);
            JsonNode ps = body == null ? null : jsonMapper.readTree(body);
            String model = properties.ollama().model();
            for (JsonNode entry : Json.list(ps, "models")) {
                if (ModelStatus.matches(model, Json.text(entry, "name"))
                        || ModelStatus.matches(model, Json.text(entry, "model"))) {
                    Integer context = Json.integer(entry, "context_length");
                    return context == null || context == properties.ollama().contextTokens();
                }
            }
            return false;
        } catch (RuntimeException e) {
            log.info("AI warm-up: could not ask Ollama which models are loaded: {}", e.toString());
            return true;                                    // unreachable or unsure: nothing to warm now
        }
    }

    /** Ollama's "only load" request: a chat without messages, with the chat's num_ctx. */
    private boolean load() {
        try {
            ollama.post().uri(baseUrl() + "/api/chat").contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("model", properties.ollama().model(), "messages", List.of(), "stream", false,
                            "options", Map.of("num_ctx", properties.ollama().contextTokens())))
                    .retrieve().toBodilessEntity();
            return true;
        } catch (RuntimeException e) {
            log.warn("AI warm-up: could not load {}: {}", properties.ollama().model(), e.toString());
            return false;
        }
    }

    /**
     * One model call that starts like a real turn of this assistant: its rules (the first system message) and the tools
     * offered to the start's roles, which the model's template puts right after them. Nothing else of a real turn (the
     * history, the user's context line, the question) is part of that shared start. Streamed, so that
     * {@link #stopReading} can close it; STOPPED when someone began waiting before or during it.
     */
    private Outcome read(Start start) {
        AgentDefinition agent = start.agent();
        long started = System.nanoTime();
        CompletableFuture<Boolean> done = new CompletableFuture<>();
        try {
            CurrentUser user = new CurrentUser(WARM_UP_USER, null, null, start.roles(), null, null);
            ToolTurn turn = ToolTurn.of(user, new CallContext(null, null), agent, null, null, null);
            Flux<ChatResponse> answer = chatClient.prompt()
                    .messages(new SystemMessage(agent.systemPrompt()), new UserMessage(QUESTION))
                    .toolCallbacks(guard.callbacks(turn))          // definitions only: no advisor runs a tool here
                    .options(OllamaChatOptions.builder().numPredict(1))
                    .stream()
                    .chatResponse();
            synchronized (this) {
                if (someoneWaiting) {
                    return Outcome.STOPPED;                         // began since the warm-up gave way: not now
                }
                readDone = done;
                reading = answer.subscribe(ignored -> { }, done::completeExceptionally, () -> done.complete(true));
            }
            if (!done.get(LOAD_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
                log.info("AI warm-up: someone is waiting for the model; stopped reading the {} assistant's start for {}"
                        + " after {} s, read again later", agent.id(), start.roles(), seconds(started));
                return Outcome.STOPPED;
            }
            log.info("AI warm-up: the {} assistant's rules and tools for {} read in {} s", agent.id(), start.roles(),
                    seconds(started));
            return Outcome.READ;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();                     // the service is stopping
            return Outcome.STOPPED;
        } catch (ExecutionException | TimeoutException | RuntimeException e) {
            Throwable cause = e instanceof ExecutionException failed && failed.getCause() != null ? failed.getCause() : e;
            log.warn("AI warm-up: reading the {} assistant's start for {} failed after {} s: {}", agent.id(),
                    start.roles(), seconds(started), cause.toString());
            return Outcome.FAILED;
        } finally {
            synchronized (this) {
                if (reading != null) {
                    reading.dispose();                              // a timeout or an interrupt: close it too
                }
                reading = null;
                readDone = null;
            }
        }
    }

    private String baseUrl() {
        String url = properties.ollama().baseUrl();
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    private static long seconds(long startedNanos) {
        return Duration.ofNanos(System.nanoTime() - startedNanos).toSeconds();
    }
}
