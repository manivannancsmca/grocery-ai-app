package com.grocery.ai.chat;

import com.grocery.ai.approval.ApprovalView;
import com.grocery.ai.tool.ToolActivity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * The chat stream over {@link SseEmitter}: one JSON object per {@code data:}, named events. Every send is flushed at
 * once (tokens appear as the model produces them). Thread-safe: tokens come from the model's stream, tool events from
 * the thread running the tool calls. A failed write means the client is gone; later sends are dropped.
 */
public class SseChatSink implements ChatSink {

    private static final Logger log = LoggerFactory.getLogger(SseChatSink.class);

    private final SseEmitter emitter;
    private final JsonMapper jsonMapper;
    private volatile boolean closed;

    public SseChatSink(SseEmitter emitter, JsonMapper jsonMapper) {
        this.emitter = emitter;
        this.jsonMapper = jsonMapper;
        emitter.onCompletion(() -> closed = true);
        emitter.onTimeout(() -> closed = true);
        emitter.onError(error -> closed = true);
    }

    @Override
    public void conversation(UUID conversationId, String agent) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("conversationId", conversationId);
        data.put("agent", agent);
        send("conversation", data);
    }

    @Override
    public void tool(ToolActivity activity) {
        send("tool", activity);
    }

    @Override
    public void approval(ApprovalView approval) {
        send("approval", approval);
    }

    @Override
    public void token(String text) {
        send("token", Map.of("text", text));
    }

    @Override
    public void done(UUID messageId, String finishReason) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("messageId", messageId);
        data.put("finishReason", finishReason);
        send("done", data);
    }

    @Override
    public void error(String code, String message) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("code", code);
        data.put("message", message);
        send("error", data);
    }

    @Override
    public void notice(String code, String message) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("code", code);
        data.put("message", message);
        send("notice", data);
    }

    @Override
    public void retry(String code, String message) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("code", code);
        data.put("message", message);
        send("retry", data);
    }

    @Override
    public boolean isClosed() {
        return closed;
    }

    @Override
    public synchronized void complete() {
        if (!closed) {
            closed = true;
            emitter.complete();
        }
    }

    private synchronized void send(String event, Object data) {
        if (closed) {
            return;
        }
        try {
            emitter.send(SseEmitter.event().name(event).data(jsonMapper.writeValueAsString(data)));
        } catch (IOException | IllegalStateException e) {
            closed = true;
            log.debug("Chat stream closed by the client while sending {}: {}", event, e.toString());
        }
    }
}
