import { formatPrice } from '../../shared/utils/format'

/** @typedef {import('../../shared/components/ui').BadgeTone} BadgeTone */
/** @typedef {import('./api').CouponStoreType} CouponStoreType */
/** @typedef {import('./api').CouponView} CouponView */
/** @typedef {import('./api').DiscountType} DiscountType */

/** @type {Record<DiscountType, string>} */
export const DISCOUNT_TYPE_LABELS = {
  PERCENT: 'Percentage off',
  FLAT: 'Amount off',
  FREE_DELIVERY: 'Free delivery',
}

/** @type {Record<CouponStoreType, string>} */
export const COUPON_STORE_TYPE_LABELS = {
  ANY: 'Every store',
  GROCERY: 'Grocery stores',
  RESTAURANT: 'Restaurants',
}

/**
 * "10% off up to ₹100", "₹50 off", "Free delivery".
 * @param {Pick<CouponView, 'discountType' | 'value' | 'maxDiscount'>} coupon
 * @returns {string}
 */
export function couponBenefit(coupon) {
  switch (coupon.discountType) {
    case 'PERCENT':
      return `${coupon.value ?? 0}% off${coupon.maxDiscount ? ` up to ${formatPrice(coupon.maxDiscount)}` : ''}`
    case 'FLAT':
      return `${formatPrice(coupon.value ?? 0)} off`
    case 'FREE_DELIVERY':
      return 'Free delivery'
    default:
      return 'Discount'
  }
}

/** @typedef {'ACTIVE' | 'PAUSED' | 'SCHEDULED' | 'EXPIRED' | 'USED_UP'} CouponState */

/**
 * Where a coupon stands now: paused, not started, expired, used up, or usable.
 * @param {Pick<CouponView, 'active' | 'validFrom' | 'validUntil' | 'totalUsageLimit' | 'usedCount'>} coupon
 * @param {number} now
 * @returns {CouponState}
 */
export function couponState(coupon, now) {
  if (Date.parse(coupon.validUntil) <= now) return 'EXPIRED'
  if (!coupon.active) return 'PAUSED'
  if (coupon.totalUsageLimit !== null && coupon.usedCount >= coupon.totalUsageLimit) return 'USED_UP'
  if (Date.parse(coupon.validFrom) > now) return 'SCHEDULED'
  return 'ACTIVE'
}

/** @type {Record<CouponState, string>} */
export const COUPON_STATE_LABELS = {
  ACTIVE: 'Active',
  PAUSED: 'Paused',
  SCHEDULED: 'Scheduled',
  EXPIRED: 'Expired',
  USED_UP: 'Used up',
}

/** @type {Record<CouponState, BadgeTone>} */
export const COUPON_STATE_TONES = {
  ACTIVE: 'green',
  PAUSED: 'amber',
  SCHEDULED: 'blue',
  EXPIRED: 'gray',
  USED_UP: 'violet',
}

/** @type {Record<string, string>} */
export const REDEMPTION_STATUS_LABELS = {
  RESERVED: 'Reserved',
  CONFIRMED: 'Confirmed',
  RELEASED: 'Released',
}
