package com.grocery.notification.controller;

import com.grocery.common.security.CurrentUser;
import com.grocery.notification.dto.EmailDtos.Preferences;
import com.grocery.notification.dto.EmailDtos.PreferencesRequest;
import com.grocery.notification.email.AddressBook;
import com.grocery.notification.service.EmailSettingsService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Phase 12 §5: the caller's email preferences — {@code { "email": { "orders": true, "account": true, "approvals": true } }}
 * (default all on). Any authenticated user, only their own. Each call also keeps the caller's address and roles in the
 * address projection ({@link AddressBook}).
 */
@RestController
@RequestMapping("/api/notifications/preferences")
@PreAuthorize("isAuthenticated()")
public class NotificationPreferencesController {

    private final EmailSettingsService settings;
    private final AddressBook addressBook;

    public NotificationPreferencesController(EmailSettingsService settings, AddressBook addressBook) {
        this.settings = settings;
        this.addressBook = addressBook;
    }

    @GetMapping
    public Preferences get(CurrentUser user) {
        addressBook.learn(user);
        return settings.preferences(user);
    }

    /** A missing category keeps its value. */
    @PutMapping
    public Preferences put(CurrentUser user, @RequestBody PreferencesRequest request) {
        addressBook.learn(user);
        return settings.update(user, request);
    }
}
