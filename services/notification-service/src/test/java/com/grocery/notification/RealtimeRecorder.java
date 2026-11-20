package com.grocery.notification;

import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Predicate;

/**
 * Records every message published on the Redis channel {@code grocery:realtime} (subscribed like a replica), so tests
 * can check exactly what was published: kind, audiences and payload.
 */
final class RealtimeRecorder implements MessageListener {

    private final JsonMapper json;
    private final List<JsonNode> messages = new CopyOnWriteArrayList<>();

    RealtimeRecorder(JsonMapper json) {
        this.json = json;
    }

    @Override
    public void onMessage(Message message, byte[] pattern) {
        messages.add(json.readTree(new String(message.getBody(), StandardCharsets.UTF_8)));
    }

    List<JsonNode> matching(Predicate<JsonNode> predicate) {
        return messages.stream().filter(predicate).toList();
    }

    /** Waits until at least {@code count} messages match, then returns all matching ones. */
    List<JsonNode> await(Predicate<JsonNode> predicate, int count, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            List<JsonNode> found = matching(predicate);
            if (found.size() >= count) {
                return found;
            }
            sleep(20);
        }
        return matching(predicate);
    }

    /** Messages whose payload mentions this value anywhere (an order, product, restaurant or account id). */
    static Predicate<JsonNode> mentioning(Object value) {
        String text = String.valueOf(value);
        return message -> message.get("payload").toString().contains(text);
    }

    static Predicate<JsonNode> signal(String type) {
        return message -> "signal".equals(message.path("kind").asString())
                && type.equals(message.path("payload").path("type").asString());
    }

    static Predicate<JsonNode> kind(String kind) {
        return message -> kind.equals(message.path("kind").asString());
    }

    static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
