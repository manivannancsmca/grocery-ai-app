package com.grocery.pricing.dto;

import com.grocery.common.export.ExportSort;
import com.grocery.common.export.ExportSort.Key;
import com.grocery.common.export.ExportTable;
import com.grocery.pricing.dto.CouponDtos.CouponView;
import com.grocery.pricing.entity.Coupon;

/**
 * The CSV / Excel / PDF exports of the coupon lists: the admin list (every coupon, with its scope and store) and a
 * store owner's own coupons (vendor or restaurant). Each shows the same fields as its list, never more.
 */
public final class CouponExports {

    private CouponExports() {
    }

    /** Newest first like the lists; a client may sort by code or by the end of validity (never-null columns). */
    public static final ExportSort<Coupon> SORT = ExportSort.<Coupon>by(
                    Key.desc("createdAt", Coupon::getCreatedAt), Key.desc("id", Coupon::getId))
            .option("code", "code", Coupon::getCode)
            .option("validity", "validUntil", coupon -> coupon.terms().validUntil())
            .build();

    /** {@code GET /api/admin/coupons/export}: every coupon with its scope and store (no personal data). */
    public static final ExportTable<CouponView> ADMIN_COUPONS = terms(ExportTable.<CouponView>named(
                    "coupons", "Coupons")
            .text("Code", CouponView::code)
            .text("Name", CouponView::name)
            .label("Scope", CouponView::scope)
            .label("Store type", CouponView::storeType)
            .text("Store", CouponView::storeName)
            .text("Store ID", CouponView::storeId))
            .build();

    /** {@code GET /api/vendor/coupons/export} and {@code /api/restaurant/coupons/export}: the store's own coupons. */
    public static final ExportTable<CouponView> STORE_COUPONS = terms(ExportTable.<CouponView>named(
                    "my-coupons", "My coupons")
            .text("Code", CouponView::code)
            .text("Name", CouponView::name))
            .build();

    /** The columns every coupon export has after its own: the discount, the validity, the limits and the usage. */
    private static ExportTable.Builder<CouponView> terms(ExportTable.Builder<CouponView> builder) {
        return builder
                .label("Discount type", CouponView::discountType)
                .decimal("Value (% or INR)", CouponView::value)
                .money("Max discount (INR)", CouponView::maxDiscount)
                .money("Min order (INR)", CouponView::minOrderSubtotal)
                .dateTime("Valid from", CouponView::validFrom)
                .dateTime("Valid until", CouponView::validUntil)
                .integer("Usage limit", CouponView::totalUsageLimit)
                .integer("Per customer", CouponView::perCustomerLimit)
                .yesNo("Public", CouponView::publicOffer)
                .label("Status", CouponView::status)
                .integer("Used", CouponView::usedCount)
                .money("Discount given (INR)", CouponView::discountGiven)
                .dateTime("Created", CouponView::createdAt);
    }
}
