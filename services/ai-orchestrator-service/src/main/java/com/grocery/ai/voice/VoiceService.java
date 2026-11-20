package com.grocery.ai.voice;

import com.grocery.ai.agent.Agents;
import com.grocery.ai.audit.AiMetrics;
import com.grocery.ai.config.AiProperties;
import com.grocery.ai.config.AiProperties.VoiceMode;
import com.grocery.common.security.CurrentUser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Voice for the assistants (speech to text for questions, text to speech for answers). Voice is only another way to
 * type and to read: a transcript goes into the chat input and is sent through the normal chat API, with all its checks
 * (agent, role, rate limit, ToolGuard). Nothing here talks to the model or the business APIs.
 *
 * <p>Every call is authenticated (the caller must have an assistant), limited per user and hour, validated (size,
 * real audio format, text length) and bounded in time. Logs carry sizes, formats and timings only, never the audio or
 * the words.
 */
@Service
public class VoiceService {

    public static final String TRANSCRIPTION = "transcription";
    public static final String SPEECH = "speech";
    private static final Logger log = LoggerFactory.getLogger(VoiceService.class);
    /** A language tag such as "en", "hi" or "en-IN"; the speech service gets the language part. */
    private static final Pattern LANGUAGE = Pattern.compile("[A-Za-z]{2,3}(?:-[A-Za-z0-9]{2,8})?");

    private final AiProperties.Voice voice;
    private final OpenAiAudioClient client;
    private final VoiceRateLimiter rateLimiter;
    private final Agents agents;
    private final AiMetrics metrics;

    public VoiceService(AiProperties properties, OpenAiAudioClient client, VoiceRateLimiter rateLimiter,
                        Agents agents, AiMetrics metrics) {
        this.voice = properties.voice();
        this.client = client;
        this.rateLimiter = rateLimiter;
        this.agents = agents;
        this.metrics = metrics;
        if (voice.mode() == VoiceMode.SERVER && voice.baseUrl().isBlank()) {
            throw new IllegalStateException("grocery.ai.voice.mode=server needs grocery.ai.voice.base-url "
                    + "(AI_VOICE_BASE_URL), e.g. https://api.openai.com/v1 or http://localhost:8000/v1");
        }
    }

    /**
     * What the web app should do: {@code mode} SERVER (record and send the audio here), BROWSER (use the browser's
     * own speech features) or OFF. A user without an assistant always sees OFF.
     */
    public record Status(String mode, boolean transcription, boolean speech, int maxRecordingSeconds,
                         long maxAudioBytes, int maxSpeechCharacters) {
    }

    /** The recognised text, to put into the chat input. */
    public record Transcript(String text) {
    }

    public Status status(CurrentUser user) {
        VoiceMode mode = agents.availableTo(user).isEmpty() ? VoiceMode.OFF : voice.mode();
        boolean server = mode == VoiceMode.SERVER;
        return new Status(mode.name(), server, server, voice.maxRecordingSeconds(), voice.maxAudioBytes(),
                voice.maxSpeechCharacters());
    }

    public Transcript transcribe(CurrentUser user, MultipartFile audio, String language) {
        requireServerVoice(user);
        if (audio == null || audio.isEmpty()) {
            throw VoiceException.empty();
        }
        if (audio.getSize() > voice.maxAudioBytes()) {
            throw VoiceException.tooLarge(voice.maxAudioBytes());
        }
        String spokenLanguage = language(language);
        byte[] bytes;
        try {
            bytes = audio.getBytes();
        } catch (IOException e) {
            throw VoiceException.unreadable();
        }
        AudioFormat format = AudioFormat.detect(bytes).orElseThrow(VoiceException::unsupportedFormat);
        limit(user);
        long started = System.nanoTime();
        String outcome = AiMetrics.SUCCESS;
        try {
            String text = client.transcribe(bytes, format, spokenLanguage).strip();
            if (text.isEmpty()) {
                outcome = VoiceException.NO_SPEECH_DETECTED;
                throw VoiceException.noSpeech();
            }
            log.info("Voice transcription: {} bytes of {} in {} ms, {} characters", bytes.length, format.extension,
                    millis(started), text.length());
            return new Transcript(text);
        } catch (VoiceException e) {
            outcome = e.errorCode();
            throw e;
        } finally {
            metrics.voiceCall(TRANSCRIPTION, outcome, System.nanoTime() - started);
        }
    }

    /** MP3 audio of an answer; markdown is read as plain words. */
    public byte[] speak(CurrentUser user, String text) {
        requireServerVoice(user);
        String plain = SpeechText.plain(text);
        if (plain.isEmpty()) {
            throw VoiceException.validation("text", "must not be blank");
        }
        if (plain.length() > voice.maxSpeechCharacters()) {
            throw VoiceException.validation("text", "size must be at most " + voice.maxSpeechCharacters()
                    + " characters");
        }
        limit(user);
        long started = System.nanoTime();
        String outcome = AiMetrics.SUCCESS;
        try {
            byte[] audio = client.speak(plain);
            log.info("Voice speech: {} characters to {} bytes in {} ms", plain.length(), audio.length,
                    millis(started));
            return audio;
        } catch (VoiceException e) {
            outcome = e.errorCode();
            throw e;
        } finally {
            metrics.voiceCall(SPEECH, outcome, System.nanoTime() - started);
        }
    }

    private void requireServerVoice(CurrentUser user) {
        if (agents.availableTo(user).isEmpty()) {
            throw VoiceException.notAllowed();
        }
        if (voice.mode() != VoiceMode.SERVER) {
            throw VoiceException.disabled();
        }
    }

    private void limit(CurrentUser user) {
        long retryAfter = rateLimiter.acquire(user.userId(), voice.requestsPerHour());
        if (retryAfter > 0) {
            throw VoiceException.rateLimited(voice.requestsPerHour(), retryAfter);
        }
    }

    /** The request's language, else the configured default; blank = let the speech service detect it. */
    private String language(String requested) {
        String value = requested == null || requested.isBlank() ? voice.language() : requested.strip();
        if (value == null || value.isBlank()) {
            return null;
        }
        if (!LANGUAGE.matcher(value).matches()) {
            throw VoiceException.validation("language", "must be a language code such as en or hi");
        }
        return value.split("-")[0].toLowerCase(Locale.ROOT);
    }

    private static long millis(long startedNanos) {
        return (System.nanoTime() - startedNanos) / 1_000_000;
    }
}
