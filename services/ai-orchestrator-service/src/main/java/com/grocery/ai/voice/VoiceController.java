package com.grocery.ai.voice;

import com.grocery.common.security.CurrentUser;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * The voice API of the assistants. The caller comes from the validated JWT ({@link CurrentUser}), never from the
 * request; the gateway routes {@code /api/ai/**} here with its per-user request rate limit.
 * <ul>
 *   <li>{@code GET /api/ai/voice/status} → {@link VoiceService.Status}</li>
 *   <li>{@code POST /api/ai/voice/transcriptions} (multipart part {@code audio}, optional {@code language}) →
 *       {@code { text }}</li>
 *   <li>{@code POST /api/ai/voice/speech} {@code { text }} → {@code audio/mpeg}</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/ai/voice")
@PreAuthorize("isAuthenticated()")
public class VoiceController {

    private final VoiceService voice;

    public VoiceController(VoiceService voice) {
        this.voice = voice;
    }

    public record SpeechRequest(String text) {
    }

    @GetMapping("/status")
    public VoiceService.Status status(CurrentUser user) {
        return voice.status(user);
    }

    @PostMapping(value = "/transcriptions", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public VoiceService.Transcript transcribe(@RequestPart(name = "audio", required = false) MultipartFile audio,
                                              @RequestParam(required = false) String language, CurrentUser user) {
        return voice.transcribe(user, audio, language);
    }

    @PostMapping("/speech")
    public ResponseEntity<byte[]> speak(@RequestBody(required = false) SpeechRequest request, CurrentUser user) {
        byte[] audio = voice.speak(user, request == null ? null : request.text());
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("audio/mpeg"))
                .cacheControl(CacheControl.noStore())
                .body(audio);
    }
}
