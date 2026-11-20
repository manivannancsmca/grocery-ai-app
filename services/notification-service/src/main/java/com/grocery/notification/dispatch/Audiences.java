package com.grocery.notification.dispatch;

import com.grocery.common.security.Roles;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Audience keys: {@code user:<uuid>}, {@code role:<ROLE>} and {@code public}. A connection's audiences are computed on
 * the server from the validated token (never chosen by the client); a notification or signal is addressed to
 * audience keys resolved from the server's own projections.
 */
public final class Audiences {

    public static final String PUBLIC = "public";
    private static final Pattern ROLE = Pattern.compile("[A-Z][A-Z0-9_]{0,59}");

    private Audiences() {
    }

    public static String user(UUID userId) {
        return "user:" + userId;
    }

    public static String role(String role) {
        return "role:" + role;
    }

    /** {@code role:<R>} for each role, in a stable order. */
    public static List<String> roles(Collection<String> roles) {
        return roles.stream().sorted().map(Audiences::role).toList();
    }

    /** Every admin role ({@code ADMIN_ORDERS_CHANGED}). */
    public static List<String> allAdmins() {
        return roles(Roles.ADMIN_ROLES);
    }

    /**
     * Phase 9: the delivery monitor's roles — SUPER_ADMIN, OPS_ADMIN, DELIVERY_MANAGER ({@code ADMIN_DELIVERIES_CHANGED},
     * {@code NO_DELIVERY_PARTNER}).
     */
    public static List<String> deliveryAdmins() {
        return roles(List.of(Roles.SUPER_ADMIN, Roles.OPS_ADMIN, Roles.DELIVERY_MANAGER));
    }

    /** Phase 9: who decides about a failed delivery — SUPER_ADMIN, OPS_ADMIN, SUPPORT, DELIVERY_MANAGER. */
    public static List<String> deliveryFailureAdmins() {
        return roles(List.of(Roles.SUPER_ADMIN, Roles.OPS_ADMIN, Roles.SUPPORT, Roles.DELIVERY_MANAGER));
    }

    /** Phase 10: who looks after stuck orders — SUPER_ADMIN, OPS_ADMIN, SUPPORT ({@code STUCK_ORDER}). */
    public static List<String> stuckOrderAdmins() {
        return roles(List.of(Roles.SUPER_ADMIN, Roles.OPS_ADMIN, Roles.SUPPORT));
    }

    /**
     * Phase 12: who decides flagged reviews and escalated returns — SUPER_ADMIN, OPS_ADMIN, SUPPORT
     * ({@code REVIEW_FLAGGED}, {@code RETURN_ESCALATED}).
     */
    public static List<String> moderators() {
        return roles(List.of(Roles.SUPER_ADMIN, Roles.OPS_ADMIN, Roles.SUPPORT));
    }

    /**
     * Phase 11: the role names an event names (an approval request's {@code approverRoles}), sorted and de-duplicated;
     * a blank or malformed name is left out (it could never match a token's role anyway).
     */
    public static List<String> wellFormedRoles(Collection<String> roles) {
        if (roles == null) {
            return List.of();
        }
        return roles.stream()
                .filter(role -> role != null && ROLE.matcher(role).matches())
                .distinct().sorted().toList();
    }

    /** The audiences of a caller's inbox: {@code user:<sub>} and {@code role:<R>} for each well-formed role. */
    public static Set<String> inbox(UUID userId, Collection<String> roles) {
        Set<String> audiences = new LinkedHashSet<>();
        audiences.add(user(userId));
        if (roles != null) {
            new TreeSet<>(roles).stream()
                    .filter(role -> role != null && ROLE.matcher(role).matches())
                    .forEach(role -> audiences.add(role(role)));
        }
        return audiences;
    }

    /** The audiences of a stream: the inbox audiences plus {@code public}. */
    public static Set<String> stream(UUID userId, Collection<String> roles) {
        Set<String> audiences = inbox(userId, roles);
        audiences.add(PUBLIC);
        return audiences;
    }
}
