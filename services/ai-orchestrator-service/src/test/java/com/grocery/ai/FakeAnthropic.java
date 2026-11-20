package com.grocery.ai;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;

/**
 * Speaks Anthropic's Messages API ({@code GET /v1/models/{model}}, {@code POST /v1/messages} streamed as server-sent
 * events or not) so the real Spring AI Anthropic chat model and the official Java SDK run in the tests. Script: a
 * request that offers tools and asks about the cart gets a {@code getCart} tool call; a request carrying a tool result
 * gets the answer in two text deltas; a non-streamed request gets an insight report. A wrong {@code x-api-key} is
 * refused with 401, and {@link #failWith(int, String)} makes the next requests fail (529 overloaded).
 */
public final class FakeAnthropic {

    public static final String KEY = "sk-ant-test-key";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final HttpServer server;
    private final List<JsonNode> messageRequests = new CopyOnWriteArrayList<>();
    private final List<Map<String, String>> modelChecks = new CopyOnWriteArrayList<>();
    private volatile String knownModel = "claude-opus-5-5";
    private volatile int failStatus;
    private volatile String failType;

    public FakeAnthropic() {
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        server.setExecutor(Executors.newCachedThreadPool());
        server.createContext("/v1/models/", this::model);
        server.createContext("/v1/messages", this::messages);
        server.start();
    }

    public String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    public void knownModel(String model) {
        knownModel = model;
    }

    /** From now on, {@code POST /v1/messages} answers this error (0: answer normally again). */
    public void failWith(int status, String errorType) {
        failStatus = status;
        failType = errorType;
    }

    public List<JsonNode> messageRequests() {
        return List.copyOf(messageRequests);
    }

    /** The headers of each model check: {@code x-api-key}, {@code anthropic-version}. */
    public List<Map<String, String>> modelChecks() {
        return List.copyOf(modelChecks);
    }

    private void model(HttpExchange exchange) throws IOException {
        Map<String, String> headers = new ConcurrentHashMap<>();
        headers.put("x-api-key", String.valueOf(exchange.getRequestHeaders().getFirst("x-api-key")));
        headers.put("anthropic-version", String.valueOf(exchange.getRequestHeaders().getFirst("anthropic-version")));
        modelChecks.add(headers);
        String model = exchange.getRequestURI().getPath().substring("/v1/models/".length());
        if (!KEY.equals(exchange.getRequestHeaders().getFirst("x-api-key"))) {
            error(exchange, 401, "authentication_error", "invalid x-api-key");
        } else if (!model.equals(knownModel)) {
            error(exchange, 404, "not_found_error", "model: " + model);
        } else {
            send(exchange, 200, "application/json", "{\"type\":\"model\",\"id\":\"" + model + "\",\"display_name\":"
                    + "\"Claude\",\"created_at\":\"2026-01-01T00:00:00Z\"}");
        }
    }

    private void messages(HttpExchange exchange) throws IOException {
        JsonNode request = JSON.readTree(exchange.getRequestBody().readAllBytes());
        messageRequests.add(request);
        if (!KEY.equals(exchange.getRequestHeaders().getFirst("x-api-key"))) {
            error(exchange, 401, "authentication_error", "invalid x-api-key");
            return;
        }
        if (failStatus != 0) {
            error(exchange, failStatus, failType, "Overloaded");
            return;
        }
        String model = request.get("model").asString();
        if (!request.path("stream").asBoolean(false)) {
            String report = "{\"headline\":\"Steady day\",\"kpis\":[{\"name\":\"Orders\",\"value\":\"42\","
                    + "\"note\":null}],\"observations\":[\"Orders are normal.\"],\"risks\":[],"
                    + "\"suggestedActions\":[\"No action needed.\"]}";
            send(exchange, 200, "application/json", "{\"id\":\"msg_insights\",\"type\":\"message\",\"role\":"
                    + "\"assistant\",\"model\":\"" + model + "\",\"content\":[{\"type\":\"text\",\"text\":"
                    + JSON.writeValueAsString(report) + "}],\"stop_reason\":\"end_turn\",\"stop_sequence\":null,"
                    + "\"usage\":{\"input_tokens\":10,\"output_tokens\":20}}");
            return;
        }
        boolean toolResult = request.get("messages").valueStream().anyMatch(m -> m.path("content").isArray()
                && m.get("content").valueStream().anyMatch(c -> "tool_result".equals(c.path("type").asString())));
        String lastUser = request.get("messages").valueStream()
                .filter(m -> "user".equals(m.get("role").asString())).reduce((a, b) -> b)
                .map(FakeAnthropic::text).orElse("");
        StringBuilder events = new StringBuilder();
        event(events, "message_start", "{\"type\":\"message_start\",\"message\":{\"id\":\"msg_1\",\"type\":\"message\","
                + "\"role\":\"assistant\",\"model\":\"" + model + "\",\"content\":[],\"stop_reason\":null,"
                + "\"stop_sequence\":null,\"usage\":{\"input_tokens\":3200,\"output_tokens\":1,"
                + "\"cache_creation_input_tokens\":0,\"cache_read_input_tokens\":3000}}}");
        String stopReason;
        if (!toolResult && request.has("tools") && lastUser.toLowerCase().contains("cart")) {
            event(events, "content_block_start", "{\"type\":\"content_block_start\",\"index\":0,\"content_block\":"
                    + "{\"type\":\"tool_use\",\"id\":\"toolu_01\",\"name\":\"getCart\",\"input\":{}}}");
            event(events, "content_block_delta", "{\"type\":\"content_block_delta\",\"index\":0,\"delta\":"
                    + "{\"type\":\"input_json_delta\",\"partial_json\":\"{}\"}}");
            stopReason = "tool_use";
        } else {
            event(events, "content_block_start", "{\"type\":\"content_block_start\",\"index\":0,\"content_block\":"
                    + "{\"type\":\"text\",\"text\":\"\"}}");
            for (String part : toolResult ? List.of("Your cart ", "has 3 items.") : List.of("Hello!")) {
                event(events, "content_block_delta", "{\"type\":\"content_block_delta\",\"index\":0,\"delta\":"
                        + "{\"type\":\"text_delta\",\"text\":" + JSON.writeValueAsString(part) + "}}");
            }
            stopReason = "end_turn";
        }
        event(events, "content_block_stop", "{\"type\":\"content_block_stop\",\"index\":0}");
        event(events, "message_delta", "{\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"" + stopReason
                + "\",\"stop_sequence\":null},\"usage\":{\"output_tokens\":12}}");
        event(events, "message_stop", "{\"type\":\"message_stop\"}");
        send(exchange, 200, "text/event-stream", events.toString());
    }

    /** A message's text: its content string, or its text blocks. */
    private static String text(JsonNode message) {
        JsonNode content = message.path("content");
        if (content.isString()) {
            return content.asString();
        }
        StringBuilder text = new StringBuilder();
        content.valueStream().filter(c -> "text".equals(c.path("type").asString()))
                .forEach(c -> text.append(c.path("text").asString()));
        return text.toString();
    }

    private static void event(StringBuilder events, String name, String data) {
        events.append("event: ").append(name).append('\n').append("data: ").append(data).append("\n\n");
    }

    private static void error(HttpExchange exchange, int status, String type, String message) throws IOException {
        send(exchange, status, "application/json", "{\"type\":\"error\",\"error\":{\"type\":\"" + type
                + "\",\"message\":\"" + message + "\"}}");
    }

    private static void send(HttpExchange exchange, int status, String contentType, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", contentType);
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }
}
