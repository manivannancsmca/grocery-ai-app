package com.grocery.delivery.partner.dto;

import com.grocery.common.export.ExportSort.Key;
import com.grocery.common.export.ExportSort.Order;
import com.grocery.common.export.ExportTable;
import com.grocery.delivery.partner.dto.PartnerDtos.PartnerView;
import com.grocery.delivery.partner.entity.DeliveryPartner;

/** The CSV / Excel / PDF export of the admin partner list. */
public final class PartnerExports {

    private PartnerExports() {
    }

    /** The review queue (PENDING_APPROVAL), oldest submission first, like the list (JPQL paths, alias {@code p}). */
    public static final Order<DeliveryPartner> REVIEW_QUEUE = Order.of(
            Key.asc("p.submittedAt", DeliveryPartner::getSubmittedAt), Key.asc("p.id", DeliveryPartner::getId));

    /** Every other list: most recently changed first, like the list. */
    public static final Order<DeliveryPartner> RECENTLY_CHANGED = Order.of(
            Key.desc("p.updatedAt", DeliveryPartner::getUpdatedAt), Key.desc("p.id", DeliveryPartner::getId));

    /**
     * {@code GET /api/admin/delivery-partners/export}. E-mail and phone are personal ({@link ExportTable#visibleTo});
     * the vehicle registration and driving licence numbers are identity documents and never exported.
     */
    public static final ExportTable<PartnerView> PARTNERS = ExportTable.<PartnerView>named(
                    "delivery-partners", "Delivery partners")
            .text("Name", PartnerView::fullName)
            .text("Email", PartnerView::email).personal()
            .text("Phone", PartnerView::phone).personal()
            .text("City", PartnerView::city)
            .label("Status", PartnerView::status)
            .label("Vehicle", PartnerView::vehicleType)
            .text("Status reason", PartnerView::statusReason)
            .dateTime("Submitted", PartnerView::submittedAt)
            .dateTime("Approved", PartnerView::approvedAt)
            .dateTime("Registered", PartnerView::createdAt)
            .dateTime("Last change", PartnerView::updatedAt)
            .build();
}
