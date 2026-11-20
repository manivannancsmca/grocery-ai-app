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
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;

/**
 * Speaks Ollama's HTTP API ({@code GET /api/tags}, {@code POST /api/chat} streamed as NDJSON or not) so the real
 * Spring AI Ollama client and chat model run in the tests. Script: a request that offers tools and asks about the
 * cart gets a {@code getCart} tool call (without an id, as older Ollama versions send it); a request carrying a tool
 * result gets the answer in two chunks; a non-streamed request with a {@code format} schema gets an insight report.
 */
public final class FakeOllama {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final HttpServer server;
    private final List<JsonNode> chatRequests = new CopyOnWriteArrayList<>();
    private volatile String pulledModel = "qwen3:4b";

    public FakeOllama() {
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        server.setExecutor(Executors.newCachedThreadPool());
        server.createContext("/api/tags", this::tags);
        server.createContext("/api/chat", this::chat);
        server.start();
    }

    public String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    public void pulledModel(String model) {
        pulledModel = model;
    }

    public List<JsonNode> chatRequests() {
        return List.copyOf(chatRequests);
    }

    /** From now on, connections are refused. */
    public void stop() {
        server.stop(0);
    }

    private void tags(HttpExchange exchange) throws IOException {
        send(exchange, "application/json", "{\"models\":[{\"name\":\"" + pulledModel + "\",\"model\":\"" + pulledModel
                + "\",\"size\":2500000000}]}");
    }

    private void chat(HttpExchange exchange) throws IOException {
        JsonNode request = JSON.readTree(exchange.getRequestBody().readAllBytes());
        chatRequests.add(request);
        String model = request.get("model").asString();
        boolean stream = request.path("stream").asBoolean(true);
        boolean toolResult = request.get("messages").valueStream()
                .anyMatch(m -> "tool".equals(m.get("role").asString()));
        String lastUser = request.get("messages").valueStream()
                .filter(m -> "user".equals(m.get("role").asString())).reduce((a, b) -> b)
                .map(m -> m.get("content").asString()).orElse("");
        if (!stream) {
            String report = "{\"headline\":\"Steady day\",\"kpis\":[{\"name\":\"Orders\",\"value\":\"42\","
                    + "\"note\":null}],\"observations\":[\"Orders are normal.\"],\"risks\":[],"
                    + "\"suggestedActions\":[\"No action needed.\"]}";
            send(exchange, "application/json", "{\"model\":\"" + model + "\",\"created_at\":\"2026-11-13T09:00:00Z\","
                    + "\"message\":{\"role\":\"assistant\",\"content\":" + JSON.writeValueAsString(report) + "},"
                    + "\"done\":true,\"done_reason\":\"stop\",\"prompt_eval_count\":10,\"eval_count\":20}");
            return;
        }
        StringBuilder lines = new StringBuilder();
        if (!toolResult && request.has("tools") && lastUser.toLowerCase().contains("cart")) {
            lines.append(chunk(model, "\"content\":\"\",\"tool_calls\":[{\"function\":{\"name\":\"getCart\","
                    + "\"arguments\":{}}}]", false)).append('\n');
        } else if (toolResult) {
            lines.append(chunk(model, "\"content\":\"Your cart \"", false)).append('\n');
            lines.append(chunk(model, "\"content\":\"has 3 items.\"", false)).append('\n');
        } else {
            lines.append(chunk(model, "\"content\":\"Hello!\"", false)).append('\n');
        }
        lines.append(chunk(model, "\"content\":\"\"", true)).append('\n');
        send(exchange, "application/x-ndjson", lines.toString());
    }

    private static String chunk(String model, String message, boolean done) {
        return "{\"model\":\"" + model + "\",\"created_at\":\"2026-11-13T09:00:00Z\",\"message\":{\"role\":"
                + "\"assistant\"," + message + "},\"done\":" + done
                + (done ? ",\"done_reason\":\"stop\",\"prompt_eval_count\":10,\"eval_count\":5,\"total_duration\":1000,"
                + "\"load_duration\":10,\"prompt_eval_duration\":100,\"eval_duration\":500" : "") + "}";
    }

    private static void send(HttpExchange exchange, String contentType, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", contentType);
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }
}
