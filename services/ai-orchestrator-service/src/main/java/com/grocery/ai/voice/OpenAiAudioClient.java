package com.grocery.ai.voice;

import com.grocery.ai.config.AiProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.InterruptedIOException;
import java.net.http.HttpClient;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Speech to text and text to speech through an OpenAI-compatible audio API (OpenAI, or a self-hosted server such as
 * speaches): {@code POST {baseUrl}/audio/transcriptions} (multipart: file, model, language?, response_format=json) and
 * {@code POST {baseUrl}/audio/speech} (JSON: model, voice, input, response_format=mp3).
 *
 * <p>Plain {@link RestClient} calls with explicit timeouts, no SDK: the contract is two small endpoints, and every
 * outcome is mapped here to a {@link VoiceException} the web app understands. Neither the audio nor the text is ever
 * logged.
 */
@Component
public class OpenAiAudioClient {

    private static final Logger log = LoggerFactory.getLogger(OpenAiAudioClient.class);
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);

    private final AiProperties.Voice voice;
    private final RestClient client;
    private final JsonMapper jsonMapper;

    public OpenAiAudioClient(AiProperties properties, RestClient.Builder restClientBuilder, JsonMapper jsonMapper) {
        this.voice = properties.voice();
        this.jsonMapper = jsonMapper;
        HttpClient httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(CONNECT_TIMEOUT)
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(voice.timeout());
        this.client = restClientBuilder.clone().requestFactory(factory).build();
    }

    /** The recognised text (may be blank when nothing was said). */
    public String transcribe(byte[] audio, AudioFormat format, String language) {
        String filename = "recording." + format.extension;
        MultipartBodyBuilder form = new MultipartBodyBuilder();
        form.part("file", new ByteArrayResource(audio) {
            @Override
            public String getFilename() {
                return filename;
            }
        }).contentType(MediaType.parseMediaType(format.mediaType)).filename(filename);
        form.part("model", voice.transcriptionModel());
        form.part("response_format", "json");
        if (language != null && !language.isBlank()) {
            form.part("language", language);
        }
        byte[] answer = call("transcription", "/audio/transcriptions", MediaType.MULTIPART_FORM_DATA, form.build());
        JsonNode json;
        try {
            json = jsonMapper.readTree(answer);
        } catch (RuntimeException notJson) {
            log.warn("Voice transcription: the speech service answered something other than JSON");
            throw VoiceException.unavailable();
        }
        return json.path("text").asString("");
    }

    /** MP3 audio of {@code text}. */
    public byte[] speak(String text) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", voice.speechModel());
        body.put("voice", voice.speechVoice());
        body.put("input", text);
        body.put("response_format", "mp3");
        byte[] audio = call("speech", "/audio/speech", MediaType.APPLICATION_JSON, body);
        if (audio.length == 0) {
            log.warn("Voice speech: the speech service answered no audio");
            throw VoiceException.unavailable();
        }
        return audio;
    }

    private byte[] call(String operation, String path, MediaType contentType, Object body) {
        long started = System.nanoTime();
        try {
            return client.post()
                    .uri(stripTrailingSlash(voice.baseUrl()) + path)
                    .headers(headers -> {
                        if (!voice.apiKey().isBlank()) {
                            headers.set(HttpHeaders.AUTHORIZATION, "Bearer " + voice.apiKey());
                        }
                    })
                    .contentType(contentType)
                    .body(body)
                    .exchange((request, response) -> {
                        int status = response.getStatusCode().value();
                        byte[] raw = response.getBody().readAllBytes();
                        if (status >= 200 && status < 300) {
                            return raw;
                        }
                        // Only the status is logged: an error body may repeat the user's text.
                        log.warn("Voice {}: the speech service answered HTTP {}", operation, status);
                        // 400 / 415 / 422 on a transcription: the provider could not decode the recording.
                        if ("transcription".equals(operation) && (status == 400 || status == 415 || status == 422)) {
                            throw VoiceException.unreadable();
                        }
                        throw VoiceException.unavailable();
                    });
        } catch (ResourceAccessException e) {
            if (isTimeout(e, Duration.ofNanos(System.nanoTime() - started))) {
                log.warn("Voice {}: no complete answer within {}", operation, voice.timeout());
                throw VoiceException.timeout();
            }
            log.warn("Voice {}: the speech service is unreachable: {}", operation, e.getMessage());
            throw VoiceException.unavailable();
        }
    }

    /**
     * A timeout, by its exception or by the time it took: a server that streams its answer (speaches sends the audio
     * headers at once) is cut off while the body is read, which the JDK client reports as a plain "closed" I/O error.
     */
    private boolean isTimeout(Throwable failure, Duration elapsed) {
        if (elapsed.compareTo(voice.timeout().minusMillis(250)) >= 0) {
            return true;
        }
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof HttpTimeoutException || cause instanceof InterruptedIOException) {
                return true;
            }
        }
        return false;
    }

    private static String stripTrailingSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
