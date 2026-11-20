package com.grocery.notification.realtime;

import tools.jackson.databind.JsonNode;

import java.util.List;

/**
 * One message on the Redis channel {@code grocery:realtime}, published once per notification / signal / session
 * after the event's transaction committed:
 * <pre>{@code { "kind": "notification" | "signal" | "session", "audiences": ["user:…", "role:…", "public"], "payload": {…} }}</pre>
 * {@code payload} is the SSE {@code data}: a {@code NotificationView}, a {@code Signal} or {@code {"reason": …}}.
 * Every replica writes it to each of its local connections whose audiences intersect {@link #audiences()}, once.
 */
public record RealtimeMessage(String kind, List<String> audiences, JsonNode payload) {

    public static final String NOTIFICATION = "notification";
    public static final String SIGNAL = "signal";
    public static final String SESSION = "session";
}
