package com.grocery.notification.dispatch;

import com.grocery.notification.entity.NotificationType;

import java.util.Map;

/** The text of one persistent notification, before it is addressed and stored. */
public record NotificationDraft(NotificationType type, String title, String body, String link,
                                Map<String, Object> data) {
}
