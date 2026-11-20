package com.grocery.delivery.partner.mapper;

import com.grocery.delivery.partner.dto.PartnerDtos;
import com.grocery.delivery.partner.dto.PartnerDtos.PartnerView;
import com.grocery.delivery.partner.dto.PartnerDtos.StatusChangeView;
import com.grocery.delivery.partner.entity.DeliveryPartner;
import com.grocery.delivery.partner.entity.PartnerStatusChange;
import com.grocery.delivery.partner.entity.VehicleType;

public final class PartnerMapper {

    private PartnerMapper() {
    }

    public static PartnerView toView(DeliveryPartner p) {
        return new PartnerView(p.getId(), p.getEmail(), p.getStatus().name(), p.getFullName(), p.getPhone(),
                p.getCity(), p.getVehicleType() == null ? null : p.getVehicleType().name(), p.getVehicleNumber(),
                p.getDrivingLicenseNumber(), p.getStatusReason(), p.getSubmittedAt(), p.getApprovedAt(),
                p.getCreatedAt(), p.getUpdatedAt());
    }

    public static StatusChangeView toView(PartnerStatusChange c) {
        return new StatusChangeView(c.getFromStatus() == null ? null : c.getFromStatus().name(),
                c.getToStatus().name(), c.getReason(), c.getChangedBy(), c.getChangedAt());
    }

    /** Already validated against the enum's names by the request pattern. */
    public static VehicleType toVehicleType(String value) {
        return value == null ? null : VehicleType.valueOf(value);
    }

    /** Vehicle / licence number in its stored form: KA01AB1234. */
    public static String toCode(String value) {
        return PartnerDtos.normalizeCode(trim(value));
    }

    public static String trim(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
