package com.grocery.common.deadletter;

import com.grocery.common.security.Roles;
import com.grocery.common.web.ForbiddenOperationException;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Set;

/**
 * Authorisation of the dead-letter API, enforced by the library itself (it does not rely on a service's
 * SecurityFilterChain or on method security being enabled): any admin role ({@link Roles#ADMIN_ROLES}) may read,
 * SUPER_ADMIN and OPS_ADMIN may replay and discard. The acting user is the JWT subject (the user id).
 */
final class DeadLetterAccess {

    static final Set<String> OPERATORS = Set.of(Roles.SUPER_ADMIN, Roles.OPS_ADMIN);

    private DeadLetterAccess() {
    }

    /** @return the caller's user id */
    static String requireAdmin() {
        return require(Roles.ADMIN_ROLES);
    }

    /** @return the caller's user id */
    static String requireOperator() {
        return require(OPERATORS);
    }

    private static String require(Set<String> allowed) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
                || authentication instanceof AnonymousAuthenticationToken) {
            throw DeadLetterException.unauthenticated();
        }
        for (GrantedAuthority authority : authentication.getAuthorities()) {
            String name = authority.getAuthority();
            if (name != null && name.startsWith("ROLE_") && allowed.contains(name.substring("ROLE_".length()))) {
                return authentication.getName();
            }
        }
        throw new ForbiddenOperationException("FORBIDDEN", "You are not allowed to perform this operation.");
    }
}
