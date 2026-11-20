package com.grocery.apitests.support;

import io.restassured.path.json.JsonPath;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * One assistant turn: {@code POST /api/ai/agents/{agent}/chat} answered as {@code text/event-stream}
 * (conversation, tool*, approval*, token*, done | error). The scripted model answers in well under a second.
 */
public final class Chat {

    /** One SSE event. */
    public record Event(String name, String data) {

        public JsonPath json() {
            return JsonPath.from(data);
        }
    }

    private final Res response;
    private final List<Event> events;

    private Chat(Res response, List<Event> events) {
        this.response = response;
        this.events = events;
    }

    public static Chat send(Session user, String agent, String message) {
        return send(user, agent, message, null);
    }

    public static Chat send(Session user, String agent, String message, String conversationId) {
        Map<String, Object> body = conversationId == null ? Json.obj("message", message)
                : Json.obj("conversationId", conversationId, "message", message);
        Res r = Api.as(user).accept("text/event-stream").body(body).post("/api/ai/agents/" + agent + "/chat");
        return new Chat(r, r.status() == 200 ? parse(r.body()) : List.of());
    }

    static List<Event> parse(String stream) {
        List<Event> events = new ArrayList<>();
        String name = "message";
        StringBuilder data = new StringBuilder();
        for (String line : stream.replace("\r\n", "\n").split("\n", -1)) {
            if (line.isEmpty()) {
                if (!data.isEmpty()) {
                    events.add(new Event(name, data.toString()));
                }
                name = "message";
                data.setLength(0);
            } else if (line.startsWith("event:")) {
                name = line.substring(6).strip();
            } else if (line.startsWith("data:")) {
                if (!data.isEmpty()) {
                    data.append('\n');
                }
                String value = line.substring(5);
                data.append(value.startsWith(" ") ? value.substring(1) : value);
            }
        }
        if (!data.isEmpty()) {
            events.add(new Event(name, data.toString()));
        }
        return events;
    }

    public Res response() {
        return response;
    }

    public int status() {
        return response.status();
    }

    public List<Event> events() {
        return events;
    }

    public List<Event> events(String name) {
        return events.stream().filter(e -> e.name().equals(name)).toList();
    }

    public String conversationId() {
        return events("conversation").stream().findFirst().map(e -> e.json().getString("conversationId"))
                .orElse(null);
    }

    /** The final status of a tool in this turn (STARTED → SUCCEEDED | FAILED | BLOCKED | APPROVAL_REQUIRED). */
    public String toolStatus(String tool) {
        String status = null;
        for (Event e : events("tool")) {
            if (tool.equals(e.json().getString("tool"))) {
                status = e.json().getString("status");
            }
        }
        return status;
    }

    public Optional<JsonPath> lastTool(String tool) {
        JsonPath found = null;
        for (Event e : events("tool")) {
            if (tool.equals(e.json().getString("tool"))) {
                found = e.json();
            }
        }
        return Optional.ofNullable(found);
    }

    public JsonPath approval() {
        List<Event> approvals = events("approval");
        assertThat(approvals).as("approval event in %s", this).isNotEmpty();
        return approvals.getLast().json();
    }

    public String approvalId() {
        return approval().getString("id");
    }

    public String answer() {
        StringBuilder text = new StringBuilder();
        events("token").forEach(e -> text.append(e.json().getString("text")));
        return text.toString();
    }

    public Chat streamed() {
        assertThat(status()).as("chat HTTP status: %s", response.describe()).isEqualTo(200);
        assertThat(response.contentType()).as("chat content type").startsWith("text/event-stream");
        assertThat(events).as("SSE events").isNotEmpty();
        assertThat(events.getFirst().name()).as("first event of %s", this).isEqualTo("conversation");
        assertThat(events.getLast().name()).as("last event of %s", this).isEqualTo("done");
        return this;
    }

    public Chat tool(String tool, String expectedStatus) {
        assertThat(toolStatus(tool)).as("status of tool %s in %s", tool, this).isEqualTo(expectedStatus);
        return this;
    }

    @Override
    public String toString() {
        StringBuilder out = new StringBuilder("chat[" + status() + "]");
        for (Event e : events) {
            out.append("\n  ").append(e.name()).append(": ").append(Json.abbreviate(e.data(), 300));
        }
        if (events.isEmpty()) {
            out.append(' ').append(Json.abbreviate(response.body(), 500));
        }
        return out.toString();
    }
}
