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
 * Speaks Ollama's HTTP API ({@code GET /api/tags}, {@code POST /api/chat} streamed as NDJSON or not,
 * {@code POST /api/embed}) so the real Spring AI Ollama client, chat model and embedding model run in the tests. Script: a request that offers tools and asks about the
 * cart gets a {@code getCart} tool call (without an id, as older Ollama versions send it); a request carrying a tool
 * result gets the answer in two chunks; a non-streamed request with a {@code format} schema gets an insight report.
 */
public final class FakeOllama {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final HttpServer server;
    private final List<JsonNode> chatRequests = new CopyOnWriteArrayList<>();
    private final List<String> embeddedTexts = new CopyOnWriteArrayList<>();
    private volatile String pulledModel = "qwen3:4b";
    private volatile int embeddingDimensions = EMBEDDING_DIMENSIONS;
    /** 0 = answer; otherwise the HTTP status every embedding request gets (404 = model not installed). */
    private volatile int embeddingFailure;

    /** Length of the fake embedding vectors (tests set grocery.ai.knowledge.embedding.dimensions to it). */
    public static final int EMBEDDING_DIMENSIONS = 64;

    public FakeOllama() {
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        server.setExecutor(Executors.newCachedThreadPool());
        server.createContext("/api/tags", this::tags);
        server.createContext("/api/chat", this::chat);
        server.createContext("/api/embed", this::embed);
        server.start();
    }

    /** Every text embedded so far (with its task prefix). */
    public List<String> embeddedTexts() {
        return List.copyOf(embeddedTexts);
    }

    public void failEmbeddings(int httpStatus) {
        embeddingFailure = httpStatus;
    }

    public void embeddingDimensions(int dimensions) {
        embeddingDimensions = dimensions;
    }

    public void resetEmbeddings() {
        embeddingFailure = 0;
        embeddingDimensions = EMBEDDING_DIMENSIONS;
        embeddedTexts.clear();
    }

    /**
     * {@code POST /api/embed}: a bag-of-words vector per input (each word, without a trailing "s", adds 1 at a
     * position given by its hash; then normalised), so texts sharing words are similar and others are not, as with a
     * real model. The task prefixes ("search_document: ", "search_query: ") and a few filler words are ignored.
     */
    private void embed(HttpExchange exchange) throws IOException {
        JsonNode request = JSON.readTree(exchange.getRequestBody().readAllBytes());
        if (embeddingFailure != 0) {
            byte[] error = ("{\"error\":\"model \\\"" + request.path("model").asString() + "\\\" not found, try "
                    + "pulling it first\"}").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(embeddingFailure, error.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(error);
            }
            return;
        }
        JsonNode input = request.get("input");
        List<String> texts = input.isArray() ? input.valueStream().map(JsonNode::asString).toList()
                : List.of(input.asString());
        embeddedTexts.addAll(texts);
        StringBuilder embeddings = new StringBuilder("[");
        for (String text : texts) {
            if (embeddings.length() > 1) {
                embeddings.append(',');
            }
            embeddings.append(JSON.writeValueAsString(bagOfWords(text, embeddingDimensions)));
        }
        embeddings.append(']');
        send(exchange, "application/json", "{\"model\":\"" + request.path("model").asString()
                + "\",\"embeddings\":" + embeddings + ",\"total_duration\":1000,\"load_duration\":10,"
                + "\"prompt_eval_count\":" + texts.size() + "}");
    }

    private static final java.util.Set<String> FILLER = java.util.Set.of("the", "a", "an", "is", "are", "to", "of",
            "and", "or", "in", "on", "for", "your", "you", "my", "i", "me", "we", "our", "what", "how", "can", "do",
            "does", "it", "be", "with", "search_document", "search_query");

    static float[] bagOfWords(String text, int dimensions) {
        float[] vector = new float[dimensions];
        for (String word : text.toLowerCase().split("[^\\p{L}\\p{N}_]+")) {
            if (word.isEmpty() || FILLER.contains(word)) {
                continue;
            }
            String stem = word.length() > 3 && word.endsWith("s") ? word.substring(0, word.length() - 1) : word;
            vector[Math.floorMod(stem.hashCode(), dimensions)] += 1;
        }
        double norm = 0;
        for (float value : vector) {
            norm += value * value;
        }
        if (norm == 0) {
            vector[0] = 1;                       // never a zero vector (cosine needs a length)
            return vector;
        }
        float length = (float) Math.sqrt(norm);
        for (int i = 0; i < vector.length; i++) {
            vector[i] /= length;
        }
        return vector;
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
