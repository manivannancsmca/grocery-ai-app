package com.grocery.identity.mapper;

import com.grocery.identity.dto.AuthDtos.AdminUserView;
import com.grocery.identity.dto.AuthDtos.BusinessView;
import com.grocery.identity.dto.AuthDtos.UserView;
import com.grocery.identity.entity.BusinessMembership;
import com.grocery.identity.entity.UserAccount;

import java.util.List;
import java.util.UUID;

/** Entity → API view mapping (plain Java: explicit and easy to follow). */
public final class UserMapper {

    private UserMapper() {
    }

    public static UserView toView(UserAccount user, List<BusinessMembership> memberships) {
        return new UserView(user.getId(), user.getEmail(), user.getFullName(), user.getPhone(),
                user.getStatus().name(), sortedRoles(user),
                activeBusiness(memberships, BusinessMembership.BusinessType.VENDOR),
                activeBusiness(memberships, BusinessMembership.BusinessType.RESTAURANT),
                memberships.stream()
                        .map(m -> new BusinessView(m.getBusinessType().name(), m.getBusinessId(), m.getStatus().name()))
                        .toList());
    }

    public static AdminUserView toAdminView(UserAccount user) {
        return new AdminUserView(user.getId(), user.getEmail(), user.getFullName(), user.getPhone(),
                sortedRoles(user), user.getStatus().name(), user.getSuspensionReason(),
                user.getCreatedAt(), user.getLastLoginAt());
    }

    private static List<String> sortedRoles(UserAccount user) {
        return user.getRoles().stream().sorted().toList();
    }

    private static UUID activeBusiness(List<BusinessMembership> memberships, BusinessMembership.BusinessType type) {
        return memberships.stream()
                .filter(m -> m.getBusinessType() == type && m.isActive())
                .map(BusinessMembership::getBusinessId)
                .findFirst().orElse(null);
    }
}
