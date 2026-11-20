package com.grocery.ai.config;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.time.Clock;
import java.time.Duration;

@Configuration(proxyBeanMethods = false)
public class AiConfig {

    /** UTC, truncated to microseconds = the precision of DATETIME(6), so a returned timestamp is what is stored. */
    @Bean
    Clock clock() {
        return Clock.tick(Clock.systemUTC(), Duration.ofNanos(1_000));
    }

    /**
     * Runs chat turns (model stream, tool calls, SSE writes) and model calls with a deadline: one virtual thread per
     * task, so a slow local model never ties up a platform thread. Phase 13: a task continues the trace of the request
     * that started it (the current span is put in scope on the task's thread), so the model and tool calls of a chat
     * turn are spans of the user's trace.
     */
    @Bean
    AsyncTaskExecutor aiTaskExecutor(ObjectProvider<Tracer> tracer) {
        SimpleAsyncTaskExecutor executor = new SimpleAsyncTaskExecutor("ai-");
        executor.setVirtualThreads(true);
        executor.setTaskDecorator(task -> {
            Tracer current = tracer.getIfAvailable();
            Span span = current == null ? null : current.currentSpan();
            if (span == null) {
                return task;
            }
            return () -> {
                try (Tracer.SpanInScope ignored = current.withSpan(span)) {
                    task.run();
                }
            };
        });
        return executor;
    }

    /**
     * The model's memory: a window over Spring AI's JDBC repository ({@code SPRING_AI_CHAT_MEMORY} in ai_db, created by
     * Flyway). It keeps the last {@code grocery.ai.memory-messages} messages of each conversation; the full transcript
     * lives in {@code ai_message}.
     */
    @Bean
    ChatMemory chatMemory(ChatMemoryRepository chatMemoryRepository, AiProperties properties) {
        return MessageWindowChatMemory.builder()
                .chatMemoryRepository(chatMemoryRepository)
                .maxMessages(properties.memoryMessages())
                .build();
    }

    /** One client over the configured model (Ollama or the scripted stand-in); tools are attached per request. */
    @Bean
    ChatClient chatClient(ChatModel chatModel) {
        return ChatClient.builder(chatModel).build();
    }

    /** The approval expiry / recovery poller ({@code grocery.ai.approvals.pollers-enabled=false} in tests). */
    @Configuration(proxyBeanMethods = false)
    @EnableScheduling
    @ConditionalOnProperty(prefix = "grocery.ai.approvals", name = "pollers-enabled", havingValue = "true",
            matchIfMissing = true)
    static class Scheduling {
    }
}
