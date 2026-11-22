package com.grocery.ai.chat;

import com.grocery.ai.agent.Agents;
import com.grocery.ai.conversation.ConversationService;
import com.grocery.ai.gateway.CallContext;
import com.grocery.ai.model.ModelStatus;
import com.grocery.ai.model.ModelWarmUp;
import com.grocery.common.security.CurrentUser;
import com.grocery.common.web.PageResponse;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/** The chat API (contract §2). */
@RestController
@RequestMapping("/api/ai")
@PreAuthorize("isAuthenticated()")
public class ChatController {

    /** The token's roles become authorities with this prefix (GrocerySecurityAutoConfiguration). */
    private static final String ROLE_PREFIX = "ROLE_";

    private final Agents agents;
    private final ChatService chat;
    private final ConversationService conversations;
    private final ModelStatus modelStatus;
    private final ModelWarmUp warmUp;

    public ChatController(Agents agents, ChatService chat, ConversationService conversations,
                          ModelStatus modelStatus, ModelWarmUp warmUp) {
        this.agents = agents;
        this.chat = chat;
        this.conversations = conversations;
        this.modelStatus = modelStatus;
        this.warmUp = warmUp;
    }

    public record AgentView(String id, String name, String description, List<String> examplePrompts) {
    }

    /** {@code message} is validated by the service after the agent checks (so a forbidden agent is always 403). */
    public record ChatRequest(UUID conversationId, String message) {
    }

    /** Only the agents the caller may use. */
    @GetMapping("/agents")
    public List<AgentView> agents(CurrentUser user) {
        return agents.availableTo(user).stream()
                .map(a -> new AgentView(a.id(), a.name(), a.description(), a.examplePrompts())).toList();
    }

    /** The assistant panel asks this when it opens: also the moment to load the model again if it was unloaded. */
    @GetMapping("/status")
    public ModelStatus.Status status(Authentication authentication) {
        warmUp.warmIfCold(roles(authentication));
        return modelStatus.current();
    }

    /** The caller's roles from the token's authorities ({@code ROLE_X}); none for a token without roles. */
    private static Set<String> roles(Authentication authentication) {
        return authentication == null ? Set.of() : authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .filter(authority -> authority != null && authority.startsWith(ROLE_PREFIX))
                .map(authority -> authority.substring(ROLE_PREFIX.length()))
                .collect(Collectors.toUnmodifiableSet());
    }

    @PostMapping(value = "/agents/{agent}/chat", produces = {MediaType.TEXT_EVENT_STREAM_VALUE,
            MediaType.APPLICATION_JSON_VALUE})
    public ResponseEntity<SseEmitter> chat(@PathVariable String agent, @RequestBody(required = false) ChatRequest request,
                                           CurrentUser user) {
        SseEmitter emitter = chat.chat(user, CallContext.current(), agent,
                request == null ? null : request.conversationId(), request == null ? null : request.message());
        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_EVENT_STREAM)
                .cacheControl(CacheControl.noCache())
                .header("X-Accel-Buffering", "no")
                .body(emitter);
    }

    @GetMapping("/conversations")
    public PageResponse<ConversationService.ConversationSummary> conversations(
            @RequestParam(required = false) String agent, @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size, CurrentUser user) {
        return conversations.list(user, agent, page, size);
    }

    @GetMapping("/conversations/{id}")
    public ConversationService.ConversationView conversation(@PathVariable UUID id, CurrentUser user) {
        return conversations.view(user, id);
    }

    /** The new title is validated by the service (1–80 characters); only the owner may rename. */
    public record RenameRequest(String title) {
    }

    @PatchMapping("/conversations/{id}")
    public ConversationService.ConversationSummary rename(@PathVariable UUID id,
                                                         @RequestBody(required = false) RenameRequest request,
                                                         CurrentUser user) {
        return conversations.rename(user, id, request == null ? null : request.title());
    }

    @DeleteMapping("/conversations/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id, CurrentUser user) {
        conversations.delete(user, id);
        return ResponseEntity.noContent().build();
    }
}
