package com.grocery.ai.knowledge;

import com.grocery.common.security.Roles;

import java.util.EnumSet;
import java.util.Set;

/**
 * Who may read a help-center document. Every document names its audiences; a search only ever returns chunks of
 * documents meant for the signed-in user ({@link #of}). The audiences come from the validated token, never from the
 * model or the question, so a customer can never be shown a vendor's or the staff's articles.
 */
public enum KnowledgeAudience {
    CUSTOMER,
    VENDOR,
    RESTAURANT_OWNER,
    DELIVERY_PARTNER,
    /** Every admin role (SUPER_ADMIN, OPS_ADMIN, FINANCE_ADMIN, SUPPORT, DELIVERY_MANAGER). */
    STAFF;

    /** The audiences a user with these roles belongs to (empty for none). */
    public static Set<KnowledgeAudience> of(Set<String> roles) {
        Set<KnowledgeAudience> audiences = EnumSet.noneOf(KnowledgeAudience.class);
        if (roles.contains(Roles.CUSTOMER)) {
            audiences.add(CUSTOMER);
        }
        if (roles.contains(Roles.VENDOR)) {
            audiences.add(VENDOR);
        }
        if (roles.contains(Roles.RESTAURANT_OWNER)) {
            audiences.add(RESTAURANT_OWNER);
        }
        if (roles.contains(Roles.DELIVERY_PARTNER)) {
            audiences.add(DELIVERY_PARTNER);
        }
        if (roles.stream().anyMatch(Roles.ADMIN_ROLES::contains)) {
            audiences.add(STAFF);
        }
        return audiences;
    }
}
