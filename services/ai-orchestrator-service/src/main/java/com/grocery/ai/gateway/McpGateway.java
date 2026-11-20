package com.grocery.ai.gateway;

import com.grocery.ai.config.AiProperties;
import com.grocery.ai.gateway.BusinessApi.Fetch;
import com.grocery.common.correlation.CorrelationId;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.spec.McpSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * The MCP pilot's client side (2026-11-20; DEVELOPMENT_PLAN "REST vs MCP"): calls the tools catalog-query-service and
 * order-service publish on their own MCP servers, through the gateway like every REST tool call, with the user's own
 * token on every request (the AI has exactly the user's rights; each service checks the token again).
 * <p>
 * One MCP client per server, shared by every user: the servers are stateless (no session), and the MCP SDK reads the
 * caller's token per call ({@code transportContextProvider}, on the calling thread) and puts it on that call's HTTP
 * request. A server that cannot be reached (not deployed yet, down, the client broken) answers empty, and the tool
 * uses the REST API for that call; a tool's own error ({@code isError}) is a {@link Fetch.Problem} like the API's.
 */
@Component
public class McpGateway implements DisposableBean {

    /** The pilot's MCP servers. */
    public enum Server { CATALOG, ORDERS }

    private static final Logger log = LoggerFactory.getLogger(McpGateway.class);
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);
    private static final String AUTHORIZATION = "authorization";
    private static final String CORRELATION_ID = "correlationId";
    /** The caller of the MCP call running on this thread (read by the SDK through the context provider). */
    private static final ThreadLocal<CallContext> CALLER = new ThreadLocal<>();

    private final AiProperties properties;
    private final JsonMapper jsonMapper;
    private final Map<Server, McpSyncClient> clients = new EnumMap<>(Server.class);

    public McpGateway(AiProperties properties, JsonMapper jsonMapper) {
        this.properties = properties;
        this.jsonMapper = jsonMapper;
    }

    public boolean enabled() {
        return properties.mcp().enabled();
    }

    /**
     * Runs one MCP tool as {@code caller}: its JSON answer as {@link Fetch.Ok}, its error as {@link Fetch.Problem}
     * (the service's code, message and status); empty when MCP is off or the server could not be reached.
     */
    public Optional<Fetch> call(CallContext caller, Server server, String tool, Map<String, Object> arguments) {
        if (!enabled()) {
            return Optional.empty();
        }
        CALLER.set(caller);
        try {
            McpSchema.CallToolResult result = client(server).callTool(new McpSchema.CallToolRequest(tool,
                    arguments));
            return Optional.of(fetch(tool, result));
        } catch (RuntimeException e) {
            log.warn("MCP {} {} failed, the REST API answers instead: {}", server, tool, e.toString());
            forget(server);
            return Optional.empty();
        } finally {
            CALLER.remove();
        }
    }

    private Fetch fetch(String tool, McpSchema.CallToolResult result) {
        String text = result.content().stream()
                .filter(McpSchema.TextContent.class::isInstance)
                .map(content -> ((McpSchema.TextContent) content).text())
                .findFirst().orElse("");
        if (Boolean.TRUE.equals(result.isError())) {
            JsonNode error = json(text);
            String code = error == null ? null : error.path("code").asString(null);
            String message = error == null ? text : error.path("message").asString(text);
            int status = error == null ? 502 : error.path("status").asInt(502);
            return new Fetch.Problem(code == null ? "MCP_TOOL_ERROR" : code, message, status);
        }
        JsonNode body = json(text);
        if (body == null) {
            return new Fetch.Problem("MCP_TOOL_ERROR", "The " + tool + " tool did not answer with JSON.", 502);
        }
        return new Fetch.Ok(body, 200);
    }

    private JsonNode json(String text) {
        try {
            return text == null || text.isBlank() ? null : jsonMapper.readTree(text);
        } catch (RuntimeException notJson) {
            return null;
        }
    }

    /** The server's client, created and initialized (as the current caller: every request needs a token) once. */
    private synchronized McpSyncClient client(Server server) {
        McpSyncClient client = clients.get(server);
        if (client != null) {
            return client;
        }
        String endpoint = server == Server.CATALOG ? properties.mcp().catalogPath() : properties.mcp().ordersPath();
        HttpClientStreamableHttpTransport transport = HttpClientStreamableHttpTransport
                .builder(stripTrailingSlash(properties.gatewayUrl()))
                .endpoint(endpoint)
                .connectTimeout(CONNECT_TIMEOUT)
                .httpRequestCustomizer((request, method, uri, body, context) -> {
                    if (context.get(AUTHORIZATION) instanceof String authorization) {
                        request.header("Authorization", authorization);
                    }
                    if (context.get(CORRELATION_ID) instanceof String correlationId) {
                        request.header(CorrelationId.HEADER, correlationId);
                    }
                })
                .build();
        client = McpClient.sync(transport)
                .clientInfo(McpSchema.Implementation.builder("ai-orchestrator-service", "1.0.0").build())
                .requestTimeout(properties.toolTimeout())
                .initializationTimeout(properties.toolTimeout())
                .transportContextProvider(() -> context(CALLER.get()))
                .build();
        client.initialize();
        clients.put(server, client);
        log.info("MCP client for {} ready ({}{})", server, properties.gatewayUrl(), endpoint);
        return client;
    }

    /** A failed client is closed and built again on the next call. */
    private synchronized void forget(Server server) {
        McpSyncClient client = clients.remove(server);
        if (client != null) {
            try {
                client.close();
            } catch (RuntimeException ignored) {
                // already broken
            }
        }
    }

    private static McpTransportContext context(CallContext caller) {
        if (caller == null) {
            return McpTransportContext.EMPTY;
        }
        Map<String, Object> values = new HashMap<>();
        if (caller.authorization() != null) {
            values.put(AUTHORIZATION, caller.authorization());
        }
        if (caller.correlationId() != null) {
            values.put(CORRELATION_ID, caller.correlationId());
        }
        return McpTransportContext.create(values);
    }

    private static String stripTrailingSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    @Override
    public synchronized void destroy() {
        for (Server server : Server.values()) {
            forget(server);
        }
    }
}
