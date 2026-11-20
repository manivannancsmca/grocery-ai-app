import { httpClient } from '../../shared/api/httpClient'

/**
 * The customer's side of coupons (Phase 12 contract §2): the public offers of one store (pricing-service). Applying
 * and removing a code are cart writes (`features/cart`); coupon management lives in `features/coupons`.
 */

/** One active, public coupon usable at the store (`GET /api/coupons?storeId=`). */
export interface CouponOffer {
  code: string
  name: string
  description: string | null
  discountType: 'PERCENT' | 'FLAT' | 'FREE_DELIVERY' | (string & {})
  /** PERCENT: 1–90; FLAT: rupees; FREE_DELIVERY: unused. */
  value: number | null
  /** PERCENT only (optional cap). */
  maxDiscount: number | null
  /** 0 = no minimum. */
  minOrderSubtotal: number | null
  validUntil: string
}

/** Its own root (management lists live under `['coupons', owner]`). */
export const couponOfferKeys = {
  all: ['coupon-offers'] as const,
  forStore: (storeId: string) => [...couponOfferKeys.all, storeId] as const,
}

/** Signed-in customers only. */
export async function fetchCouponOffers(storeId: string): Promise<CouponOffer[]> {
  return (await httpClient.get<CouponOffer[]>('/coupons', { params: { storeId } })).data
}
