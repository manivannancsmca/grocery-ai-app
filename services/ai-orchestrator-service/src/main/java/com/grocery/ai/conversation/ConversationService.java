package com.grocery.ai.conversation;

import com.grocery.ai.agent.AgentDefinition;
import com.grocery.ai.agent.Agents;
import com.grocery.ai.approval.ApprovalService;
import com.grocery.ai.approval.ApprovalView;
import com.grocery.ai.audit.ToolInvocation;
import com.grocery.ai.audit.ToolInvocationRepository;
import com.grocery.ai.chat.ChatException;
import com.grocery.ai.link.PageLink;
import com.grocery.ai.tool.ToolActivity;
import com.grocery.ai.tool.ToolSpec;
import com.grocery.common.data.Pages;
import com.grocery.common.id.Ids;
import com.grocery.common.security.CurrentUser;
import com.grocery.common.web.PageResponse;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Conversations and their transcript ({@code conversation}, {@code ai_message}): a conversation belongs to one user and
 * one agent; another user's or another agent's conversation is "not found".
 */
@Service
public class ConversationService {

    public static final int TITLE_LENGTH = 80;

    private final ConversationRepository conversations;
    private final AiMessageRepository messages;
    private final ToolInvocationRepository invocations;
    private final ApprovalService approvals;
    private final ChatMemory chatMemory;
    private final Agents agents;
    private final TransactionTemplate transaction;
    private final JsonMapper jsonMapper;
    private final Clock clock;

    public ConversationService(ConversationRepository conversations, AiMessageRepository messages,
                               ToolInvocationRepository invocations, ApprovalService approvals, ChatMemory chatMemory,
                               Agents agents, PlatformTransactionManager transactionManager, JsonMapper jsonMapper,
                               Clock clock) {
        this.conversations = conversations;
        this.messages = messages;
        this.invocations = invocations;
        this.approvals = approvals;
        this.chatMemory = chatMemory;
        this.agents = agents;
        this.transaction = new TransactionTemplate(transactionManager);
        this.jsonMapper = jsonMapper;
        this.clock = clock;
    }

    /** A turn that has started: the user message is stored; the assistant message id is reserved. */
    public record Turn(UUID conversationId, UUID userMessageId, UUID assistantMessageId, Instant startedAt) {
    }

    public record ConversationSummary(UUID id, String agent, String title, Instant createdAt, Instant updatedAt) {
    }

    /** {@code unverified}: an answer stating figures that no tool returned (shown as not checked against live data). */
    public record MessageView(UUID id, AiMessage.Role role, String content, Instant createdAt,
                              List<ToolActivity> tools, List<ApprovalView> approvals, boolean unverified) {
    }

    public record ConversationView(UUID id, String agent, String title, List<MessageView> messages) {
    }

    /** USER messages of the user in the last hour (rate limit). */
    public long messagesSince(UUID userId, Instant since) {
        return messages.countByUserIdAndRoleAndCreatedAtAfter(userId, AiMessage.Role.USER, since);
    }

    /** When the oldest USER message since {@code since} was sent (null when none). */
    public Instant oldestMessageSince(UUID userId, Instant since) {
        return messages.findFirstByUserIdAndRoleAndCreatedAtAfterOrderByCreatedAtAsc(userId, AiMessage.Role.USER,
                since).map(AiMessage::getCreatedAt).orElse(null);
    }

    /**
     * Opens (or continues) the conversation and stores the user message — kept even if the model fails later.
     *
     * @throws ChatException CONVERSATION_NOT_FOUND for another user's or another agent's conversation
     */
    public Turn begin(CurrentUser user, AgentDefinition agent, UUID conversationId, String text) {
        return transaction.execute(status -> {
            Instant now = clock.instant();
            Conversation conversation;
            if (conversationId != null) {
                conversation = conversations.findById(conversationId)
                        .filter(c -> c.getUserId().equals(user.userId()) && c.getAgent().equals(agent.id()))
                        .orElseThrow(ChatException::conversationNotFound);
                conversation.touch(now);
            } else {
                conversation = conversations.save(new Conversation(Ids.newId(), user.userId(), agent.id(),
                        title(text), now));
            }
            AiMessage message = messages.save(new AiMessage(Ids.newId(), conversation.getId(), user.userId(),
                    AiMessage.Role.USER, text, null, null, null, now));
            return new Turn(conversation.getId(), message.getId(), Ids.newId(), now);
        });
    }

    /** The answer was streamed completely ({@code unverifiedFigures}: it states figures no tool returned). */
    public void finish(Turn turn, UUID userId, String answer, String finishReason, int latencyMs,
                       boolean unverifiedFigures) {
        transaction.executeWithoutResult(status -> {
            Instant now = clock.instant();
            AiMessage message = new AiMessage(turn.assistantMessageId(), turn.conversationId(), userId,
                    AiMessage.Role.ASSISTANT, answer, finishReason, null, latencyMs, now);
            if (unverifiedFigures) {
                message.markUnverifiedFigures();
            }
            messages.save(message);
            conversations.findById(turn.conversationId()).ifPresent(c -> c.touch(now));
        });
    }

    /**
     * The turn failed: the user message records the error; an assistant message is kept only when something happened
     * (partial text or tool activity), so the transcript shows it.
     */
    public void fail(Turn turn, UUID userId, String errorCode, String partialAnswer, int latencyMs) {
        transaction.executeWithoutResult(status -> {
            Instant now = clock.instant();
            messages.findById(turn.userMessageId()).ifPresent(message -> message.failed(errorCode));
            boolean toolActivity = !invocations.findByMessageIdInOrderByCreatedAt(
                    List.of(turn.assistantMessageId())).isEmpty();
            if (toolActivity || (partialAnswer != null && !partialAnswer.isBlank())) {
                messages.save(new AiMessage(turn.assistantMessageId(), turn.conversationId(), userId,
                        AiMessage.Role.ASSISTANT, partialAnswer == null ? "" : partialAnswer, null, errorCode,
                        latencyMs, now));
            }
            conversations.findById(turn.conversationId()).ifPresent(c -> c.touch(now));
        });
    }

    public PageResponse<ConversationSummary> list(CurrentUser user, String agent, Integer page, Integer size) {
        Pageable pageable = Pages.request(page, size, Sort.by(Sort.Order.desc("updatedAt"), Sort.Order.desc("id")));
        Page<Conversation> found = agent == null || agent.isBlank()
                ? conversations.findByUserId(user.userId(), pageable)
                : conversations.findByUserIdAndAgent(user.userId(), agent.trim(), pageable);
        return Pages.toResponse(found, c -> new ConversationSummary(c.getId(), c.getAgent(), c.getTitle(),
                c.getCreatedAt(), c.getUpdatedAt()));
    }

    public ConversationView view(CurrentUser user, UUID id) {
        Conversation conversation = owned(user, id);
        List<AiMessage> transcript = messages.findByConversationIdOrderByCreatedAtAscIdAsc(id);
        List<UUID> assistantIds = transcript.stream().filter(m -> m.getRole() == AiMessage.Role.ASSISTANT)
                .map(AiMessage::getId).toList();
        Map<UUID, List<ToolInvocation>> toolsByMessage = assistantIds.isEmpty() ? Map.of()
                : invocations.findByMessageIdInOrderByCreatedAt(assistantIds).stream()
                .collect(Collectors.groupingBy(ToolInvocation::getMessageId));
        Map<UUID, List<ApprovalView>> approvalsByMessage = approvals.byMessage(user, assistantIds);
        AgentDefinition agent = agents.find(conversation.getAgent()).orElse(null);
        List<MessageView> views = transcript.stream().map(m -> new MessageView(m.getId(), m.getRole(),
                m.getContent(), m.getCreatedAt(),
                toolsByMessage.getOrDefault(m.getId(), List.of()).stream().map(i -> activity(agent, i)).toList(),
                approvalsByMessage.getOrDefault(m.getId(), List.of()), m.hasUnverifiedFigures())).toList();
        return new ConversationView(conversation.getId(), conversation.getAgent(), conversation.getTitle(), views);
    }

    /**
     * Renames one of the user's own conversations (another user's is "not found"). The title is trimmed, inner
     * whitespace and control characters become single spaces, and it must then have 1–80 characters.
     *
     * @throws ChatException VALIDATION_FAILED for an empty or too long title, CONVERSATION_NOT_FOUND
     */
    public ConversationSummary rename(CurrentUser user, UUID id, String title) {
        String clean = title == null ? "" : title.replaceAll("[\\p{Cntrl}\\s]+", " ").strip();
        if (clean.isEmpty()) {
            throw ChatException.validation("title", "must not be blank");
        }
        if (clean.length() > TITLE_LENGTH) {
            throw ChatException.validation("title", "size must be between 1 and " + TITLE_LENGTH);
        }
        return transaction.execute(status -> {
            Conversation conversation = owned(user, id);
            conversation.rename(clean);
            return new ConversationSummary(conversation.getId(), conversation.getAgent(), conversation.getTitle(),
                    conversation.getCreatedAt(), conversation.getUpdatedAt());
        });
    }

    /** Messages and memory are deleted; audit records and approvals are kept. */
    public void delete(CurrentUser user, UUID id) {
        owned(user, id);
        transaction.executeWithoutResult(status -> {
            messages.deleteByConversation(id);
            conversations.deleteById(id);
        });
        chatMemory.clear(id.toString());
    }

    private Conversation owned(CurrentUser user, UUID id) {
        return conversations.findById(id).filter(c -> c.getUserId().equals(user.userId()))
                .orElseThrow(ChatException::conversationNotFound);
    }

    private ToolActivity activity(AgentDefinition agent, ToolInvocation invocation) {
        String label = agent == null || invocation.getTool() == null ? invocation.getTool()
                : agent.tool(invocation.getTool()).map(ToolSpec::label).orElse(invocation.getTool());
        return new ToolActivity(invocation.getId(), invocation.getTool(), label, invocation.getStatus(),
                invocation.getResultSummary(), pageLinks(invocation));
    }

    /** The links stored with the call, so a reopened conversation shows the same ones (none if unreadable). */
    private List<PageLink> pageLinks(ToolInvocation invocation) {
        if (invocation.getPageLinks() == null) {
            return List.of();
        }
        try {
            return List.of(jsonMapper.readValue(invocation.getPageLinks(), PageLink[].class));
        } catch (RuntimeException unreadable) {
            return List.of();
        }
    }

    static String title(String text) {
        String oneLine = text.strip().replaceAll("\\s+", " ");
        return oneLine.length() <= TITLE_LENGTH ? oneLine : oneLine.substring(0, TITLE_LENGTH - 1) + "…";
    }
}
