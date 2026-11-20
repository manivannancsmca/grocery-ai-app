package com.grocery.ai.audit;

import com.grocery.ai.link.PageLink;
import com.grocery.ai.tool.InvocationStatus;
import com.grocery.common.id.Ids;
import com.grocery.common.security.CurrentUser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.util.List;
import java.util.UUID;

/** Writes {@code ai_tool_invocation} rows (contract §3 "Every call is audited"). */
@Component
public class AuditLog {

    public static final String AGENT_NOT_ALLOWED = "AGENT_NOT_ALLOWED";
    private static final Logger log = LoggerFactory.getLogger(AuditLog.class);

    private final ToolInvocationRepository invocations;
    private final JsonMapper jsonMapper;
    private final Clock clock;

    public AuditLog(ToolInvocationRepository invocations, JsonMapper jsonMapper, Clock clock) {
        this.invocations = invocations;
        this.jsonMapper = jsonMapper;
        this.clock = clock;
    }

    /**
     * One finished tool call; {@code transport} (REST or MCP, the MCP pilot) only when it reached a business service.
     */
    public record Entry(UUID invocationId, UUID conversationId, UUID messageId, CurrentUser user, String agent,
                        String tool, String arguments, InvocationStatus status, String reason, String summary,
                        Integer httpStatus, Integer latencyMs, UUID approvalId, List<PageLink> links,
                        String transport) {

        public Entry(UUID invocationId, UUID conversationId, UUID messageId, CurrentUser user, String agent,
                     String tool, String arguments, InvocationStatus status, String reason, String summary,
                     Integer httpStatus, Integer latencyMs, UUID approvalId, List<PageLink> links) {
            this(invocationId, conversationId, messageId, user, agent, tool, arguments, status, reason, summary,
                    httpStatus, latencyMs, approvalId, links, null);
        }
    }

    public void record(Entry entry) {
        invocations.save(new ToolInvocation(entry.invocationId(), entry.conversationId(), entry.messageId(),
                entry.user().userId(), roles(entry.user()), entry.agent(), entry.tool(), entry.arguments(),
                entry.status(), entry.reason(), entry.summary(), entry.httpStatus(), entry.latencyMs(),
                entry.approvalId(), linksJson(entry.links()), entry.transport(), clock.instant()));
        if (entry.status() == InvocationStatus.BLOCKED) {
            log.warn("Tool call BLOCKED ({}): user {} roles {} agent {} tool {}", entry.reason(), entry.user().userId(),
                    roles(entry.user()), entry.agent(), entry.tool());
        }
    }

    /** A caller without the agent's role tried to use it (403 AGENT_NOT_ALLOWED): audited with {@code tool} null. */
    public void agentNotAllowed(CurrentUser user, String agent) {
        record(new Entry(Ids.newId(), null, null, user, agent, null, null, InvocationStatus.BLOCKED,
                AGENT_NOT_ALLOWED, "The " + agent + " assistant is not available to this user.", 403, null, null,
                List.of()));
    }

    /** The links as JSON for the {@code page_links} column, or null when there are none. */
    private String linksJson(List<PageLink> links) {
        return links == null || links.isEmpty() ? null : jsonMapper.writeValueAsString(links);
    }

    private static String roles(CurrentUser user) {
        return String.join(",", user.roles().stream().sorted().toList());
    }
}
