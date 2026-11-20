package com.grocery.identity.dto;

import com.grocery.common.export.ExportSort;
import com.grocery.common.export.ExportSort.Key;
import com.grocery.common.export.ExportTable;
import com.grocery.identity.dto.AuthDtos.AdminUserView;
import com.grocery.identity.entity.UserAccount;

import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/** The CSV / Excel / PDF export of the admin user list. */
public final class UserExports {

    private UserExports() {
    }

    /**
     * Newest first like the list (with the id as tie-breaker), or by name, status or join date (the page's column
     * keys; JPQL paths, alias {@code u}). The last sign-in may be empty and the roles are a collection: neither is a
     * sort option.
     */
    public static final ExportSort<UserAccount> SORT = ExportSort.<UserAccount>by(
                    Key.desc("u.createdAt", UserAccount::getCreatedAt), Key.desc("u.id", UserAccount::getId))
            .option("user", "u.fullName", UserAccount::getFullName)
            .option("status", "u.status", UserAccount::getStatus)
            .option("created", "u.createdAt", UserAccount::getCreatedAt)
            .build();

    /**
     * {@code GET /api/admin/users/export}. E-mail and phone are personal ({@link ExportTable#visibleTo}); password
     * hashes, refresh tokens and sign-in secrets are not in the view and never exported.
     */
    public static final ExportTable<AdminUserView> USERS = ExportTable.<AdminUserView>named("users", "Users")
            .text("Name", AdminUserView::fullName)
            .text("Email", AdminUserView::email).personal()
            .text("Phone", AdminUserView::phone).personal()
            .text("Roles", view -> roles(view.roles()))
            .label("Status", AdminUserView::status)
            .text("Suspension reason", AdminUserView::suspensionReason)
            .text("User ID", AdminUserView::id)
            .dateTime("Joined", AdminUserView::createdAt)
            .dateTime("Last sign-in", AdminUserView::lastLoginAt)
            .build();

    /** The role codes as words, as {@code .label} shows one code: "OPS_ADMIN, VENDOR" → "Ops admin, Vendor". */
    private static String roles(List<String> roles) {
        return roles == null ? null : roles.stream().map(UserExports::words).collect(Collectors.joining(", "));
    }

    private static String words(String code) {
        String spaced = code.replace('_', ' ').toLowerCase(Locale.ROOT).trim();
        return spaced.isEmpty() ? spaced : Character.toUpperCase(spaced.charAt(0)) + spaced.substring(1);
    }
}
