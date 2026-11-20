package com.grocery.ai.voice;

import com.grocery.common.web.ApiException;
import org.springframework.http.HttpStatus;

import java.util.List;
import java.util.Map;

/**
 * The voice API's errors, each with a stable {@code errorCode} the web app turns into a friendly sentence. None of them
 * affects the chat: a failed recording can always be typed instead.
 */
public class VoiceException extends ApiException {

    public static final String VOICE_DISABLED = "VOICE_DISABLED";
    public static final String VOICE_NOT_ALLOWED = "VOICE_NOT_ALLOWED";
    public static final String AUDIO_EMPTY = "AUDIO_EMPTY";
    public static final String AUDIO_TOO_LARGE = "AUDIO_TOO_LARGE";
    public static final String UNSUPPORTED_AUDIO_FORMAT = "UNSUPPORTED_AUDIO_FORMAT";
    public static final String AUDIO_UNREADABLE = "AUDIO_UNREADABLE";
    public static final String NO_SPEECH_DETECTED = "NO_SPEECH_DETECTED";
    public static final String VOICE_UNAVAILABLE = "VOICE_UNAVAILABLE";
    public static final String VOICE_TIMEOUT = "VOICE_TIMEOUT";
    public static final String VOICE_RATE_LIMITED = "VOICE_RATE_LIMITED";

    VoiceException(HttpStatus status, String errorCode, String message, Map<String, Object> properties) {
        super(status, errorCode, message, properties);
    }

    VoiceException(HttpStatus status, String errorCode, String message) {
        this(status, errorCode, message, Map.of());
    }

    static VoiceException disabled() {
        return new VoiceException(HttpStatus.CONFLICT, VOICE_DISABLED,
                "Voice is not handled by the server here (see GET /api/ai/voice/status).");
    }

    static VoiceException notAllowed() {
        return new VoiceException(HttpStatus.FORBIDDEN, VOICE_NOT_ALLOWED,
                "Voice is only available to users who have an assistant.");
    }

    static VoiceException empty() {
        return new VoiceException(HttpStatus.BAD_REQUEST, AUDIO_EMPTY, "The recording is empty.");
    }

    static VoiceException tooLarge(long maxBytes) {
        return new VoiceException(HttpStatus.CONTENT_TOO_LARGE, AUDIO_TOO_LARGE,
                "The recording is too large (at most " + (maxBytes / (1024 * 1024)) + " MB).",
                Map.of("maxBytes", maxBytes));
    }

    static VoiceException unsupportedFormat() {
        return new VoiceException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, UNSUPPORTED_AUDIO_FORMAT,
                "This audio format is not supported. Use WebM, Ogg, WAV, MP3, MP4/M4A or FLAC.",
                Map.of("supportedFormats", AudioFormat.names()));
    }

    static VoiceException unreadable() {
        return new VoiceException(HttpStatus.UNPROCESSABLE_CONTENT, AUDIO_UNREADABLE,
                "The recording could not be read as audio. Please try again.");
    }

    static VoiceException noSpeech() {
        return new VoiceException(HttpStatus.UNPROCESSABLE_CONTENT, NO_SPEECH_DETECTED,
                "No words were recognised in the recording. Please speak a little louder or closer.");
    }

    static VoiceException unavailable() {
        return new VoiceException(HttpStatus.SERVICE_UNAVAILABLE, VOICE_UNAVAILABLE,
                "The voice service is not available right now. You can type your question instead.");
    }

    static VoiceException timeout() {
        return new VoiceException(HttpStatus.GATEWAY_TIMEOUT, VOICE_TIMEOUT,
                "The voice service took too long to answer. Please try again or type your question.");
    }

    static VoiceException rateLimited(int perHour, long retryAfterSeconds) {
        return new VoiceException(HttpStatus.TOO_MANY_REQUESTS, VOICE_RATE_LIMITED,
                "You can use voice " + perHour + " times per hour. Please type for a little while.",
                Map.of("retryAfterSeconds", retryAfterSeconds));
    }

    static VoiceException validation(String field, String message) {
        return new VoiceException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Request validation failed.",
                Map.of("errors", List.of(Map.of("field", field, "message", message))));
    }
}
