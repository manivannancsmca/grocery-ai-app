package com.grocery.ai.model;

import com.anthropic.errors.AnthropicIoException;
import com.anthropic.errors.AnthropicServiceException;
import org.springframework.ai.retry.NonTransientAiException;
import org.springframework.ai.retry.TransientAiException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.io.IOException;
import java.net.ConnectException;
import java.net.UnknownHostException;
import java.net.http.HttpTimeoutException;
import java.nio.channels.UnresolvedAddressException;
import java.util.concurrent.TimeoutException;

/**
 * The stream's {@code error} codes (contract §2) with their user-friendly messages, and the classification of model
 * failures: unreachable / not pulled / server error → AI_UNAVAILABLE, no answer in time → AI_TIMEOUT, anything else
 * → AI_ERROR.
 */
public final class ModelErrors {

    public static final String AI_UNAVAILABLE = "AI_UNAVAILABLE";
    public static final String AI_TIMEOUT = "AI_TIMEOUT";
    public static final String AI_TOOL_LIMIT = "AI_TOOL_LIMIT";
    public static final String AI_ERROR = "AI_ERROR";

    private ModelErrors() {
    }

    public static String message(String code) {
        return switch (code) {
            case AI_UNAVAILABLE -> "The assistant is not available right now. Everything else works as usual.";
            case AI_TIMEOUT -> "The assistant took too long to answer. Please try again, or ask something simpler.";
            case AI_TOOL_LIMIT -> "That needed too many steps for one message. Please ask for one thing at a time.";
            default -> "Something went wrong with the assistant. Please try again.";
        };
    }

    public static String classify(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof TimeoutException || cause instanceof HttpTimeoutException
                    || cause instanceof java.net.SocketTimeoutException
                    || cause.getClass().getName().contains("ReadTimeoutException")) {
                return AI_TIMEOUT;
            }
            if (cause instanceof ConnectException || cause instanceof UnknownHostException
                    || cause instanceof UnresolvedAddressException || cause instanceof WebClientRequestException
                    || cause instanceof ResourceAccessException || cause instanceof TransientAiException
                    || cause.getClass().getName().contains("ConnectException")) {
                return containsTimeout(cause) ? AI_TIMEOUT : AI_UNAVAILABLE;
            }
            if (cause instanceof AnthropicServiceException refused) {
                // Anthropic: a wrong key (401/403), an unknown model (404), the rate limit (429), overloaded (529)
                // or a server error: the assistant is not available. A request it could not take (400, 413, 422)
                // is this service's mistake.
                int status = refused.statusCode();
                return status == 400 || status == 413 || status == 422 ? AI_ERROR : AI_UNAVAILABLE;
            }
            if (cause instanceof AnthropicIoException) {
                return containsTimeout(cause) ? AI_TIMEOUT : AI_UNAVAILABLE;
            }
            if (cause instanceof WebClientResponseException response) {
                return response.getStatusCode().is5xxServerError() || response.getStatusCode().value() == 404
                        ? AI_UNAVAILABLE : AI_ERROR;
            }
            if (cause instanceof NonTransientAiException nonTransient) {
                String message = String.valueOf(nonTransient.getMessage());
                return message.startsWith("404") || message.contains("not found") ? AI_UNAVAILABLE : AI_ERROR;
            }
            if (cause instanceof IOException) {
                return AI_UNAVAILABLE;
            }
        }
        return AI_ERROR;
    }

    private static boolean containsTimeout(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof TimeoutException || cause instanceof HttpTimeoutException
                    || cause instanceof java.net.SocketTimeoutException
                    || cause.getClass().getName().contains("Timeout")) {
                return true;
            }
        }
        return false;
    }
}
