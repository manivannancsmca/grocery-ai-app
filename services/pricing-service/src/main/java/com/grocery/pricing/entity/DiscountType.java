package com.grocery.pricing.entity;

/** How a coupon discounts: a percentage of the item subtotal, a flat amount, or the whole delivery fee. */
public enum DiscountType {
    /** {@code value} % (1-90) of the subtotal, at most {@code maxDiscount} when set. */
    PERCENT,
    /** {@code value} off the subtotal, at most the subtotal. */
    FLAT,
    /** The delivery fee is waived ({@code deliveryFeeDiscount} = the fee); no discount on the items. */
    FREE_DELIVERY
}
