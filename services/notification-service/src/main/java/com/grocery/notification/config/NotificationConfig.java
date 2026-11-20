package com.grocery.notification.config;

import com.grocery.notification.realtime.RealtimeSubscriber;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.SyncTaskExecutor;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.time.Clock;
import java.time.Duration;

@Configuration(proxyBeanMethods = false)
@EnableScheduling
public class NotificationConfig {

    /** UTC, truncated to microseconds = the precision of DATETIME(6), so a returned timestamp is what is stored. */
    @Bean
    Clock clock() {
        return Clock.tick(Clock.systemUTC(), Duration.ofNanos(1_000));
    }

    /**
     * The subscription of THIS replica to {@code grocery.notification.redis-channel}. Messages are handed to the
     * subscriber on the Redis client's thread ({@link SyncTaskExecutor}); the subscriber only queues them for its own
     * single fan-out thread, so they reach the streams in the order they were published (a notification before the
     * {@code REVOKED} that follows it) and a slow client never blocks the Redis connection.
     *
     * <p>Named {@code redisMessageListenerContainer} so Spring Boot's own container (whose virtual-thread executor
     * would dispatch messages concurrently, out of order) backs off.
     */
    @Bean
    RedisMessageListenerContainer redisMessageListenerContainer(RedisConnectionFactory connectionFactory,
                                                                RealtimeSubscriber subscriber,
                                                                NotificationProperties properties) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        container.setTaskExecutor(new SyncTaskExecutor());
        container.setRecoveryInterval(2_000);
        container.addMessageListener(subscriber, new ChannelTopic(properties.getRedisChannel()));
        return container;
    }
}
