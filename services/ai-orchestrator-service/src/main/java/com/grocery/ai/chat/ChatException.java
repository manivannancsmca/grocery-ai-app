package com.grocery.ai.chat;

import com.grocery.common.web.ApiException;
import org.springframework.http.HttpStatus;

import java.util.List;
import java.util.Map;

/** The chat API's errors (contract §2), answered before the stream starts. */
public class ChatException extends ApiException {

    public ChatException(HttpStatus status, String errorCode, String message, Map<String, Object> properties) {
        super(status, errorCode, message, properties);
    }

    public static ChatException agentNotFound(String agent) {
        return new ChatException(HttpStatus.NOT_FOUND, "AGENT_NOT_FOUND", "There is no assistant '" + agent + "'",
                Map.of());
    }

    public static ChatException agentNotAllowed(String agent) {
        return new ChatException(HttpStatus.FORBIDDEN, "AGENT_NOT_ALLOWED",
                "The " + agent + " assistant is not available to you", Map.of());
    }

    public static ChatException conversationNotFound() {
        return new ChatException(HttpStatus.NOT_FOUND, "CONVERSATION_NOT_FOUND", "Conversation not found", Map.of());
    }

    public static ChatException rateLimited(int perHour, long retryAfterSeconds) {
        return new ChatException(HttpStatus.TOO_MANY_REQUESTS, "AI_RATE_LIMITED", "You can send " + perHour
                + " messages per hour to the assistant. Please wait a little.",
                Map.of("retryAfterSeconds", retryAfterSeconds));
    }

    public static ChatException validation(String field, String message) {
        return new ChatException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Request validation failed.",
                Map.of("errors", List.of(Map.of("field", field, "message", message))));
    }
}
