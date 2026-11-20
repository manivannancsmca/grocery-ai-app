package com.grocery.notification.service;

import com.grocery.common.security.CurrentUser;
import com.grocery.common.web.NotFoundException;
import com.grocery.common.web.PageResponse;
import com.grocery.notification.dispatch.Audiences;
import com.grocery.notification.dto.NotificationDtos.NotificationView;
import com.grocery.notification.dto.NotificationDtos.ReadAllResult;
import com.grocery.notification.dto.NotificationDtos.UnreadCount;
import com.grocery.notification.repository.NotificationRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * The inbox (contract §3). "The caller's notifications" are those addressed to {@code user:<sub>} or to a
 * {@code role:<ROLE>} of the caller's token; read state is per user, so admin A reading a notification sent to
 * {@code role:OPS_ADMIN} does not mark it read for admin B. Somebody else's or an unknown id is 404
 * {@code NOTIFICATION_NOT_FOUND} (the caller cannot tell them apart).
 */
@Service
public class InboxService {

    public static final int DEFAULT_SIZE = 20;
    public static final int MAX_SIZE = 50;

    private final NotificationRepository notifications;
    private final Clock clock;

    public InboxService(NotificationRepository notifications, Clock clock) {
        this.notifications = notifications;
        this.clock = clock;
    }

    /** Newest first; {@code unread=true} → only unread; page ≥ 0, 1 ≤ size ≤ 50 (clamped). */
    @Transactional(readOnly = true)
    public PageResponse<NotificationView> list(CurrentUser user, boolean unreadOnly, Integer page, Integer size) {
        int safePage = page == null || page < 0 ? 0 : page;
        int safeSize = size == null || size < 1 ? DEFAULT_SIZE : Math.min(size, MAX_SIZE);
        Set<String> audiences = audiences(user);
        long total = notifications.count(user.userId(), audiences, unreadOnly);
        List<NotificationView> content = total == 0 ? List.of()
                : notifications.page(user.userId(), audiences, unreadOnly, safeSize, (long) safePage * safeSize);
        int totalPages = (int) ((total + safeSize - 1) / safeSize);
        return new PageResponse<>(content, safePage, safeSize, total, totalPages);
    }

    @Transactional(readOnly = true)
    public UnreadCount unreadCount(CurrentUser user) {
        return new UnreadCount(notifications.count(user.userId(), audiences(user), true));
    }

    /** Idempotent: reading a read notification again is fine. */
    @Transactional
    public void markRead(CurrentUser user, String notificationId) {
        UUID id = parse(notificationId);
        if (id == null || !notifications.isVisible(id, audiences(user))) {
            throw new NotFoundException("NOTIFICATION_NOT_FOUND", "Notification not found");
        }
        notifications.markRead(user.userId(), id, clock.instant());
    }

    @Transactional
    public ReadAllResult markAllRead(CurrentUser user) {
        return new ReadAllResult(notifications.markAllRead(user.userId(), audiences(user), clock.instant()));
    }

    private static Set<String> audiences(CurrentUser user) {
        return Audiences.inbox(user.userId(), user.roles());
    }

    private static UUID parse(String id) {
        try {
            UUID uuid = UUID.fromString(id);
            return uuid.toString().equalsIgnoreCase(id) ? uuid : null;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
