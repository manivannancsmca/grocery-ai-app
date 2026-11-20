package com.grocery.vendor.dto;

import com.grocery.common.export.ExportSort.Key;
import com.grocery.common.export.ExportSort.Order;
import com.grocery.common.export.ExportTable;
import com.grocery.vendor.dto.VendorDtos.AddressDto;
import com.grocery.vendor.dto.VendorDtos.VendorView;
import com.grocery.vendor.entity.Vendor;

import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** The CSV / Excel / PDF export of the admin vendor list. */
public final class VendorExports {

    private VendorExports() {
    }

    /** The review queue (PENDING_APPROVAL), oldest submission first, like the list (JPQL paths, alias {@code v}). */
    public static final Order<Vendor> REVIEW_QUEUE = Order.of(
            Key.asc("v.submittedAt", Vendor::getSubmittedAt), Key.asc("v.id", Vendor::getId));

    /** Every other list: most recently changed first, like the list. */
    public static final Order<Vendor> RECENTLY_CHANGED = Order.of(
            Key.desc("v.updatedAt", Vendor::getUpdatedAt), Key.desc("v.id", Vendor::getId));

    /**
     * {@code GET /api/admin/vendors/export}. The owner's e-mail, phone and street address are personal
     * ({@link ExportTable#visibleTo}); the area (city, state, PIN code) is always there. GSTIN and FSSAI licence are
     * business registrations an admin reviews; the profile holds no bank details.
     */
    public static final ExportTable<VendorView> VENDORS = ExportTable.<VendorView>named("vendors", "Vendors")
            .text("Business name", VendorView::businessName)
            .text("Legal name", VendorView::legalName)
            .text("Vendor ID", VendorView::id)
            .label("Status", VendorView::status)
            .text("GSTIN", VendorView::gstin)
            .text("FSSAI licence", VendorView::fssaiLicense)
            .text("Contact email", VendorView::contactEmail).personal()
            .text("Contact phone", VendorView::contactPhone).personal()
            .text("Street address", view -> street(view.address())).personal()
            .text("City", view -> view.address() == null ? null : view.address().city())
            .text("State", view -> view.address() == null ? null : view.address().state())
            .text("PIN code", view -> view.address() == null ? null : view.address().postalCode())
            .text("Status reason", VendorView::statusReason)
            .dateTime("Submitted", VendorView::submittedAt)
            .dateTime("Approved", VendorView::approvedAt)
            .dateTime("Registered", VendorView::createdAt)
            .dateTime("Last change", VendorView::updatedAt)
            .build();

    private static String street(AddressDto address) {
        if (address == null) {
            return null;
        }
        String joined = Stream.of(address.line1(), address.line2()).filter(Objects::nonNull).map(String::trim)
                .filter(part -> !part.isEmpty()).collect(Collectors.joining(", "));
        return joined.isEmpty() ? null : joined;
    }
}
