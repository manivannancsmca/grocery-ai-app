package com.grocery.notification.realtime;

import com.grocery.notification.config.NotificationProperties;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * PUBLISHes realtime messages on the Redis channel, in order, from one background thread: the Kafka consumer threads
 * never wait for Redis. If Redis is down the message is dropped and logged — the notification is already in the inbox
 * (committed before the publish), so the client sees it on its next list or reconnect (contract §5). The queue is
 * bounded ({@value #MAX_QUEUED} messages) so an outage cannot exhaust memory.
 */
@Component
public class RealtimePublisher {

    static final int MAX_QUEUED = 10_000;
    private static final Logger log = LoggerFactory.getLogger(RealtimePublisher.class);

    private final StringRedisTemplate redis;
    private final JsonMapper json;
    private final String channel;
    private final ThreadPoolExecutor executor = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
            new LinkedBlockingQueue<>(MAX_QUEUED), Thread.ofPlatform().name("realtime-publisher").daemon().factory());

    public RealtimePublisher(StringRedisTemplate redis, JsonMapper json, NotificationProperties properties) {
        this.redis = redis;
        this.json = json;
        this.channel = properties.getRedisChannel();
    }

    public void publish(RealtimeMessage message) {
        String text = json.writeValueAsString(message);
        try {
            executor.execute(() -> send(text, message));
        } catch (RejectedExecutionException e) {
            log.warn("Realtime queue full or stopped: {} for {} dropped (clients see it on their next refetch)",
                    message.kind(), message.audiences());
        }
    }

    private void send(String text, RealtimeMessage message) {
        try {
            redis.convertAndSend(channel, text);
        } catch (RuntimeException e) {
            log.warn("Could not publish {} for {} on Redis channel {}: {} (clients see it on their next refetch)",
                    message.kind(), message.audiences(), channel, e.toString());
        }
    }

    @PreDestroy
    void stop() throws InterruptedException {
        executor.shutdown();
        executor.awaitTermination(2, TimeUnit.SECONDS);
    }
}
