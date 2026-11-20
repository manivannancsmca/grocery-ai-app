import type { BadgeTone } from '../../shared/components/ui'
import { formatPrice } from '../../shared/utils/format'
import type { CouponStoreType, CouponView, DiscountType } from './api'

export const DISCOUNT_TYPE_LABELS: Record<DiscountType, string> = {
  PERCENT: 'Percentage off',
  FLAT: 'Amount off',
  FREE_DELIVERY: 'Free delivery',
}

export const COUPON_STORE_TYPE_LABELS: Record<CouponStoreType, string> = {
  ANY: 'Every store',
  GROCERY: 'Grocery stores',
  RESTAURANT: 'Restaurants',
}

/** "10% off up to ₹100", "₹50 off", "Free delivery". */
export function couponBenefit(coupon: Pick<CouponView, 'discountType' | 'value' | 'maxDiscount'>): string {
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

export type CouponState = 'ACTIVE' | 'PAUSED' | 'SCHEDULED' | 'EXPIRED' | 'USED_UP'

/** Where a coupon stands now: paused, not started, expired, used up, or usable. */
export function couponState(coupon: Pick<CouponView, 'active' | 'validFrom' | 'validUntil' | 'totalUsageLimit' | 'usedCount'>, now: number): CouponState {
  if (Date.parse(coupon.validUntil) <= now) return 'EXPIRED'
  if (!coupon.active) return 'PAUSED'
  if (coupon.totalUsageLimit !== null && coupon.usedCount >= coupon.totalUsageLimit) return 'USED_UP'
  if (Date.parse(coupon.validFrom) > now) return 'SCHEDULED'
  return 'ACTIVE'
}

export const COUPON_STATE_LABELS: Record<CouponState, string> = {
  ACTIVE: 'Active',
  PAUSED: 'Paused',
  SCHEDULED: 'Scheduled',
  EXPIRED: 'Expired',
  USED_UP: 'Used up',
}

export const COUPON_STATE_TONES: Record<CouponState, BadgeTone> = {
  ACTIVE: 'green',
  PAUSED: 'amber',
  SCHEDULED: 'blue',
  EXPIRED: 'gray',
  USED_UP: 'violet',
}

export const REDEMPTION_STATUS_LABELS: Record<string, string> = {
  RESERVED: 'Reserved',
  CONFIRMED: 'Confirmed',
  RELEASED: 'Released',
}
