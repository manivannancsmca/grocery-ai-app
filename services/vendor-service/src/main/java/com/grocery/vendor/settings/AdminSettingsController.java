package com.grocery.vendor.settings;

import com.grocery.common.security.CurrentUser;
import com.grocery.common.security.Roles;
import com.grocery.common.web.PageResponse;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Platform settings of this service (docs/api/platform-settings.md): any admin role may read them and their history;
 * SUPER_ADMIN and OPS_ADMIN, who run the platform, may change them.
 */
@RestController
@RequestMapping("/api/admin/settings/" + GroceryStoreSettingsService.AREA)
@PreAuthorize(Roles.ANY_ADMIN)
public class AdminSettingsController {

    private final GroceryStoreSettingsService settings;

    public AdminSettingsController(GroceryStoreSettingsService settings) {
        this.settings = settings;
    }

    @GetMapping
    public SettingsView get() {
        return settings.view();
    }

    @PutMapping
    @PreAuthorize(Roles.OPERATIONS_ADMIN)
    public SettingsView update(@Valid @RequestBody GroceryStoreSettings.Update request, CurrentUser user) {
        return settings.update(request, user);
    }

    /** Who changed which field, when, from what to what; newest first. */
    @GetMapping("/history")
    public PageResponse<SettingChange> history(@RequestParam(required = false) Integer page,
                                               @RequestParam(required = false) Integer size) {
        return settings.history(page, size);
    }
}
