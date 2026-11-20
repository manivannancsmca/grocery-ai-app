package com.grocery.notification.controller;

import com.grocery.common.security.CurrentUser;
import com.grocery.common.web.PageResponse;
import com.grocery.notification.dto.NotificationDtos.NotificationView;
import com.grocery.notification.dto.NotificationDtos.ReadAllResult;
import com.grocery.notification.dto.NotificationDtos.UnreadCount;
import com.grocery.notification.email.AddressBook;
import com.grocery.notification.service.InboxService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The caller's inbox (contract §3). Any authenticated user; only the caller's own notifications — those addressed to
 * {@code user:<sub>} or to a role of the caller's token. Read state is per user.
 */
@RestController
@RequestMapping("/api/notifications")
@PreAuthorize("isAuthenticated()")
public class NotificationController {

    private final InboxService inbox;
    private final AddressBook addressBook;

    public NotificationController(InboxService inbox, AddressBook addressBook) {
        this.inbox = inbox;
        this.addressBook = addressBook;
    }

    /** Newest first; {@code unread=true} → only unread; {@code size} ≤ 50. */
    @GetMapping
    public PageResponse<NotificationView> list(CurrentUser user,
                                               @RequestParam(required = false) Boolean unread,
                                               @RequestParam(required = false) Integer page,
                                               @RequestParam(required = false) Integer size) {
        addressBook.learn(user);     // Phase 12: the caller's address and roles for the email channel
        return inbox.list(user, Boolean.TRUE.equals(unread), page, size);
    }

    @GetMapping("/unread-count")
    public UnreadCount unreadCount(CurrentUser user) {
        return inbox.unreadCount(user);
    }

    /** 204, idempotent. Somebody else's, unknown or malformed id → 404 NOTIFICATION_NOT_FOUND. */
    @PostMapping("/{id}/read")
    public ResponseEntity<Void> read(CurrentUser user, @PathVariable String id) {
        inbox.markRead(user, id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/read-all")
    public ReadAllResult readAll(CurrentUser user) {
        return inbox.markAllRead(user);
    }
}
