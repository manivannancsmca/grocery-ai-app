package com.grocery.ai;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Voice (mode {@code server}): speech to text and text to speech through an OpenAI-compatible audio API, stood in for
 * by the FakeGateway under {@code /v1/audio/**}. Recordings are checked before anything is sent (size, real format);
 * provider failures, timeouts and silence become friendly error codes; the user's token never reaches the provider.
 */
class VoiceIntegrationTest extends AbstractIntegrationTest {

    /** The first bytes of a WebM recording (EBML header), padded. */
    private static byte[] webm(int size) {
        byte[] audio = new byte[size];
        Arrays.fill(audio, (byte) 7);
        audio[0] = 0x1A;
        audio[1] = 0x45;
        audio[2] = (byte) 0xDF;
        audio[3] = (byte) 0xA3;
        return audio;
    }

    private static MockMultipartFile recording(byte[] audio) {
        return new MockMultipartFile("audio", "question.webm", "audio/webm", audio);
    }

    @Test
    void theStatusTellsTheAppToRecordForTheServer() throws Exception {
        mvc.perform(get("/api/ai/voice/status").with(as(UUID.randomUUID(), "VENDOR")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mode").value("SERVER"))
                .andExpect(jsonPath("$.transcription").value(true))
                .andExpect(jsonPath("$.speech").value(true))
                .andExpect(jsonPath("$.maxRecordingSeconds").value(60))
                .andExpect(jsonPath("$.maxAudioBytes").value(200000));
        // A signed-in user without any assistant gets no voice.
        mvc.perform(get("/api/ai/voice/status").with(as(UUID.randomUUID())))
                .andExpect(jsonPath("$.mode").value("OFF"))
                .andExpect(jsonPath("$.transcription").value(false));
        mvc.perform(get("/api/ai/voice/status")).andExpect(status().isUnauthorized());
    }

    @Test
    void aRecordingBecomesTextWithoutSendingTheUsersToken() throws Exception {
        GATEWAY.json("POST", "/v1/audio/transcriptions", 200, "{\"text\":\"  How many vendors are approved? \"}");
        byte[] audio = webm(4096);

        mvc.perform(multipart("/api/ai/voice/transcriptions").file(recording(audio))
                        .with(as(UUID.randomUUID(), "SUPER_ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.text").value("How many vendors are approved?"));

        FakeGateway.Request call = GATEWAY.requests("POST", "/v1/audio/transcriptions").getFirst();
        assertThat(call.header("Authorization")).isEqualTo("Bearer test-voice-key");
        assertThat(call.header("Content-Type")).startsWith("multipart/form-data");
        String body = new String(call.body(), StandardCharsets.ISO_8859_1);
        assertThat(body).contains("name=\"file\"; filename=\"recording.webm\"").contains("Content-Type: audio/webm")
                .contains("name=\"model\"").contains("whisper-1").contains("name=\"response_format\"");
        assertThat(call.body().length).isGreaterThan(audio.length);
        // Nothing but the audio API was called: the chat is a separate, normal request.
        assertThat(GATEWAY.requests()).hasSize(1);
    }

    @Test
    void aLanguageHintIsPassedAsItsLanguageCode() throws Exception {
        GATEWAY.json("POST", "/v1/audio/transcriptions", 200, "{\"text\":\"mera order kahan hai\"}");
        mvc.perform(multipart("/api/ai/voice/transcriptions").file(recording(webm(2048))).param("language", "hi-IN")
                        .with(as(UUID.randomUUID(), "CUSTOMER")))
                .andExpect(status().isOk());
        String body = new String(GATEWAY.requests().getFirst().body(), StandardCharsets.ISO_8859_1);
        assertThat(body).contains("name=\"language\"").containsPattern("name=\"language\"[\\s\\S]*?\\r\\n\\r\\nhi\\r\\n");

        mvc.perform(multipart("/api/ai/voice/transcriptions").file(recording(webm(2048))).param("language", "<script>")
                        .with(as(UUID.randomUUID(), "CUSTOMER")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("language"));
    }

    @Test
    void badRecordingsAreRefusedBeforeTheProviderIsCalled() throws Exception {
        UUID user = UUID.randomUUID();
        mvc.perform(multipart("/api/ai/voice/transcriptions").file(recording(new byte[0])).with(as(user, "CUSTOMER")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("AUDIO_EMPTY"));
        mvc.perform(multipart("/api/ai/voice/transcriptions").with(as(user, "CUSTOMER")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("AUDIO_EMPTY"));
        // A file named and typed as audio, but not audio inside.
        mvc.perform(multipart("/api/ai/voice/transcriptions")
                        .file(new MockMultipartFile("audio", "fake.webm", "audio/webm",
                                "#!/bin/sh\necho not audio at all".getBytes(StandardCharsets.UTF_8)))
                        .with(as(user, "CUSTOMER")))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.errorCode").value("UNSUPPORTED_AUDIO_FORMAT"))
                .andExpect(jsonPath("$.supportedFormats[0]").value("webm"));
        mvc.perform(multipart("/api/ai/voice/transcriptions").file(recording(webm(200_001)))
                        .with(as(user, "CUSTOMER")))
                .andExpect(status().is(413))
                .andExpect(jsonPath("$.errorCode").value("AUDIO_TOO_LARGE"));
        assertThat(GATEWAY.requests()).isEmpty();
    }

    @Test
    void providerProblemsBecomeFriendlyErrors() throws Exception {
        UUID user = UUID.randomUUID();
        GATEWAY.json("POST", "/v1/audio/transcriptions", 200, "{\"text\":\"   \"}");
        mvc.perform(multipart("/api/ai/voice/transcriptions").file(recording(webm(1024))).with(as(user, "CUSTOMER")))
                .andExpect(status().is(422))
                .andExpect(jsonPath("$.errorCode").value("NO_SPEECH_DETECTED"));

        GATEWAY.json("POST", "/v1/audio/transcriptions", 400, "{\"error\":{\"message\":\"Invalid file\"}}");
        mvc.perform(multipart("/api/ai/voice/transcriptions").file(recording(webm(1024))).with(as(user, "CUSTOMER")))
                .andExpect(status().is(422))
                .andExpect(jsonPath("$.errorCode").value("AUDIO_UNREADABLE"));

        GATEWAY.json("POST", "/v1/audio/transcriptions", 500, "{\"error\":\"boom\"}");
        mvc.perform(multipart("/api/ai/voice/transcriptions").file(recording(webm(1024))).with(as(user, "CUSTOMER")))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.errorCode").value("VOICE_UNAVAILABLE"));

        GATEWAY.on("POST", "/v1/audio/transcriptions",
                request -> FakeGateway.Response.json(200, "{\"text\":\"late\"}").delayed(Duration.ofSeconds(4)));
        mvc.perform(multipart("/api/ai/voice/transcriptions").file(recording(webm(1024))).with(as(user, "CUSTOMER")))
                .andExpect(status().isGatewayTimeout())
                .andExpect(jsonPath("$.errorCode").value("VOICE_TIMEOUT"));
    }

    @Test
    void anAnswerIsReadAloudAsPlainWords() throws Exception {
        GATEWAY.json("POST", "/v1/audio/speech", 200, "ID3-fake-mp3-audio");

        byte[] audio = mvc.perform(post("/api/ai/voice/speech").with(as(UUID.randomUUID(), "SUPER_ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept("audio/mpeg", "application/problem+json")
                        .content("{\"text\":\"**112** vendors are approved.\\n- [View vendors](/admin/vendors)\"}"))
                .andExpect(status().isOk())
                .andExpect(content().contentType("audio/mpeg"))
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")))
                .andReturn().getResponse().getContentAsByteArray();

        assertThat(new String(audio, StandardCharsets.UTF_8)).isEqualTo("ID3-fake-mp3-audio");
        FakeGateway.Request call = GATEWAY.requests("POST", "/v1/audio/speech").getFirst();
        assertThat(call.header("Authorization")).isEqualTo("Bearer test-voice-key");
        assertThat(call.json().get("input").asString()).isEqualTo("112 vendors are approved. View vendors");
        assertThat(call.json().get("model").asString()).isEqualTo("tts-1");
        assertThat(call.json().get("voice").asString()).isEqualTo("alloy");
        assertThat(call.json().get("response_format").asString()).isEqualTo("mp3");
    }

    @Test
    void speechErrorsAreProblemDetailsEvenWhenAudioWasAskedFor() throws Exception {
        UUID user = UUID.randomUUID();
        mvc.perform(post("/api/ai/voice/speech").with(as(user, "CUSTOMER")).accept("audio/mpeg")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"text\":\"  \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.errors[0].field").value("text"));
        mvc.perform(post("/api/ai/voice/speech").with(as(user, "CUSTOMER")).accept("audio/mpeg")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"text\":\"" + "a".repeat(501) + "\"}"))
                .andExpect(status().isBadRequest());
        GATEWAY.json("POST", "/v1/audio/speech", 503, "{}");
        mvc.perform(post("/api/ai/voice/speech").with(as(user, "CUSTOMER")).accept("audio/mpeg")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"text\":\"hello\"}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.errorCode").value("VOICE_UNAVAILABLE"));
        // No assistant, no voice.
        mvc.perform(post("/api/ai/voice/speech").with(as(UUID.randomUUID())).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"hello\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("VOICE_NOT_ALLOWED"));
    }
}
