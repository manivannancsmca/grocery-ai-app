package com.grocery.notification.realtime;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

/**
 * This replica's side of the fan-out: every message published on {@code grocery:realtime} (by any replica) is turned
 * into its SSE frame once and handed to the {@link ConnectionRegistry}, which writes it to each local connection whose
 * audiences match. Messages are processed one at a time, in publishing order, on a single thread.
 */
@Component
public class RealtimeSubscriber implements MessageListener {

    private static final Logger log = LoggerFactory.getLogger(RealtimeSubscriber.class);

    private final ConnectionRegistry registry;
    private final JsonMapper json;
    private final ExecutorService fanout = Executors.newSingleThreadExecutor(
            Thread.ofPlatform().name("realtime-fanout").daemon().factory());

    public RealtimeSubscriber(ConnectionRegistry registry, JsonMapper json) {
        this.registry = registry;
        this.json = json;
    }

    @Override
    public void onMessage(Message message, byte[] pattern) {
        String text = new String(message.getBody(), StandardCharsets.UTF_8);
        try {
            fanout.execute(() -> dispatch(text));
        } catch (RejectedExecutionException e) {
            log.debug("Shutting down: realtime message dropped");
        }
    }

    void dispatch(String text) {
        try {
            JsonNode root = json.readTree(text);
            JsonNode payload = root.get("payload");
            List<String> audiences = new ArrayList<>();
            root.path("audiences").forEach(audience -> audiences.add(audience.asString()));
            if (payload == null || audiences.isEmpty()) {
                log.warn("Ignoring a realtime message without payload or audiences");
                return;
            }
            SseFrame frame = switch (root.path("kind").asString()) {
                case RealtimeMessage.NOTIFICATION -> SseFrame.notification(
                        UUID.fromString(payload.path("id").asString()), json.writeValueAsString(payload));
                case RealtimeMessage.SIGNAL -> SseFrame.signal(json.writeValueAsString(payload));
                case RealtimeMessage.SESSION -> SseFrame.session(json.writeValueAsString(payload));
                default -> null;
            };
            if (frame == null) {
                log.warn("Ignoring a realtime message of unknown kind {}", root.path("kind").asString());
                return;
            }
            registry.deliver(audiences, frame);
        } catch (RuntimeException e) {
            log.warn("Could not deliver a realtime message: {}", e.toString());
        }
    }

    @PreDestroy
    void stop() {
        fanout.shutdownNow();
    }
}
