package com.grocery.notification.controller;

import com.grocery.common.security.CurrentUser;
import com.grocery.notification.email.AddressBook;
import com.grocery.notification.service.StreamService;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * {@code GET /api/notifications/stream} (contract §2): Server-Sent Events for any authenticated user. The token comes
 * in the {@code Authorization} header (browsers use a fetch-based SSE client); no token → 401 like every API. The
 * connection's audiences are computed from the validated token, never chosen by the client.
 */
@RestController
@PreAuthorize("isAuthenticated()")
public class NotificationStreamController {

    public static final String LAST_EVENT_ID = "Last-Event-ID";

    private final StreamService streams;
    private final AddressBook addressBook;

    public NotificationStreamController(StreamService streams, AddressBook addressBook) {
        this.streams = streams;
        this.addressBook = addressBook;
    }

    @GetMapping("/api/notifications/stream")
    public ResponseEntity<SseEmitter> stream(@AuthenticationPrincipal Jwt jwt,
                                             @RequestHeader(name = LAST_EVENT_ID, required = false) String lastEventId) {
        // Phase 12: the caller's address and roles for the email channel (never fails the stream)
        addressBook.learn(CurrentUser.from(jwt));
        SseEmitter emitter = streams.open(jwt, lastEventId);
        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_EVENT_STREAM)
                .cacheControl(CacheControl.noCache())
                .header("X-Accel-Buffering", "no")
                .body(emitter);
    }
}
