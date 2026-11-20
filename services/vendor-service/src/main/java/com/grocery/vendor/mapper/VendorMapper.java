package com.grocery.vendor.mapper;

import com.grocery.vendor.dto.VendorDtos.AddressDto;
import com.grocery.vendor.dto.VendorDtos.StatusChangeView;
import com.grocery.vendor.dto.VendorDtos.StoreView;
import com.grocery.vendor.dto.VendorDtos.VendorView;
import com.grocery.vendor.entity.BusinessAddress;
import com.grocery.vendor.entity.Vendor;
import com.grocery.vendor.entity.VendorStatus;
import com.grocery.vendor.entity.VendorStatusChange;

public final class VendorMapper {

    private VendorMapper() {
    }

    public static VendorView toView(Vendor v) {
        return new VendorView(v.getId(), v.getOwnerUserId(), v.getStatus().name(), v.getBusinessName(),
                v.getLegalName(), v.getGstin(), v.getFssaiLicense(), v.getContactEmail(), v.getContactPhone(),
                toDto(v.getAddress()), v.getStatusReason(), v.getSubmittedAt(), v.getApprovedAt(),
                v.getCreatedAt(), v.getUpdatedAt());
    }

    /** The internal store view: {@code active} = APPROVED; no address yet → null city and coordinates. */
    public static StoreView toStoreView(Vendor v) {
        BusinessAddress a = v.getAddress();
        return new StoreView(v.getId(), v.getBusinessName(), v.getStatus().name(),
                v.getStatus() == VendorStatus.APPROVED,
                a == null ? null : a.getCity(),
                a == null ? null : a.getLatitude(),
                a == null ? null : a.getLongitude());
    }

    public static BusinessAddress toEntity(AddressDto dto) {
        if (dto == null) {
            return null;
        }
        return new BusinessAddress(trim(dto.line1()), trim(dto.line2()), trim(dto.city()), trim(dto.state()),
                trim(dto.postalCode()), dto.country() == null ? "IN" : dto.country(), dto.latitude(), dto.longitude());
    }

    public static StatusChangeView toView(VendorStatusChange c) {
        return new StatusChangeView(c.getFromStatus() == null ? null : c.getFromStatus().name(),
                c.getToStatus().name(), c.getReason(), c.getChangedBy(), c.getChangedAt());
    }

    private static AddressDto toDto(BusinessAddress a) {
        if (a == null) {
            return null;
        }
        return new AddressDto(a.getLine1(), a.getLine2(), a.getCity(), a.getState(), a.getPostalCode(),
                a.getCountry(), a.getLatitude(), a.getLongitude());
    }

    public static String trim(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
