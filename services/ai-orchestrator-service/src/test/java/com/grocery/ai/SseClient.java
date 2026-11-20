package com.grocery.ai;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/** Reads the chat stream over real HTTP, event by event (as a browser's fetch reader would). */
public final class SseClient {

    /** One server-sent event. */
    public record Event(String name, JsonNode data) {

        public String text(String field) {
            JsonNode value = data.get(field);
            return value == null || value.isNull() ? null : value.asString();
        }
    }

    /** The whole answer: status, headers and events (or the problem body when not 200). */
    public record Result(int status, Map<String, List<String>> headers, List<Event> events, String errorBody) {

        public List<String> names() {
            return events.stream().map(Event::name).toList();
        }

        public List<Event> named(String name) {
            return events.stream().filter(e -> e.name().equals(name)).toList();
        }

        public Event first(String name) {
            return named(name).getFirst();
        }

        public Event last() {
            return events.getLast();
        }

        /** The concatenated {@code token} texts after the last {@code retry} (as the web app shows the answer). */
        public String answer() {
            StringBuilder text = new StringBuilder();
            for (Event event : events) {
                if (event.name().equals("retry")) {
                    text.setLength(0);
                } else if (event.name().equals("token")) {
                    text.append(event.text("text"));
                }
            }
            return text.toString();
        }

        public JsonNode problem() {
            return JSON.readTree(errorBody);
        }

        public String header(String name) {
            for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
                if (entry.getKey().equalsIgnoreCase(name) && !entry.getValue().isEmpty()) {
                    return entry.getValue().getFirst();
                }
            }
            return null;
        }
    }

    static final JsonMapper JSON = JsonMapper.builder().build();
    private static final HttpClient CLIENT = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    private SseClient() {
    }

    public static Result post(int port, String path, String token, String body, String accept) {
        return post(port, path, token, body, accept, event -> {
        });
    }

    /** POSTs and reads the stream to its end, calling {@code onEvent} as each event arrives. */
    public static Result post(int port, String path, String token, String body, String accept,
                              Consumer<Event> onEvent) {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(60))
                .header("Content-Type", "application/json")
                .header("X-Correlation-Id", "test-corr-12345")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (accept != null) {
            request.header("Accept", accept);
        }
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        try {
            HttpResponse<InputStream> response = CLIENT.send(request.build(), HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() != 200) {
                String error = new String(response.body().readAllBytes(), StandardCharsets.UTF_8);
                return new Result(response.statusCode(), response.headers().map(), List.of(), error);
            }
            List<Event> events = new ArrayList<>();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(response.body(),
                    StandardCharsets.UTF_8))) {
                String name = null;
                StringBuilder data = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.isEmpty()) {
                        if (name != null || !data.isEmpty()) {
                            Event event = new Event(name == null ? "message" : name, JSON.readTree(data.toString()));
                            events.add(event);
                            onEvent.accept(event);
                        }
                        name = null;
                        data.setLength(0);
                    } else if (line.startsWith("event:")) {
                        name = line.substring(6).strip();
                    } else if (line.startsWith("data:")) {
                        if (!data.isEmpty()) {
                            data.append('\n');
                        }
                        data.append(line.substring(5).startsWith(" ") ? line.substring(6) : line.substring(5));
                    }
                }
            }
            return new Result(200, response.headers().map(), events, null);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
