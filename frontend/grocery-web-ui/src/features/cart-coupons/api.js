import { httpClient } from '../../shared/api/httpClient'

/**
 * The customer's side of coupons (Phase 12 contract §2): the public offers of one store (pricing-service). Applying
 * and removing a code are cart writes (`features/cart`); coupon management lives in `features/coupons`.
 */

/**
 * One active, public coupon usable at the store (`GET /api/coupons?storeId=`).
 * @typedef {object} CouponOffer
 * @property {string} code
 * @property {string} name
 * @property {string | null} description
 * @property {'PERCENT' | 'FLAT' | 'FREE_DELIVERY' | (string & {})} discountType
 * @property {number | null} value PERCENT: 1–90; FLAT: rupees; FREE_DELIVERY: unused.
 * @property {number | null} maxDiscount PERCENT only (optional cap).
 * @property {number | null} minOrderSubtotal 0 = no minimum.
 * @property {string} validUntil
 */

/** Its own root (management lists live under `['coupons', owner]`). */
export const couponOfferKeys = {
  all: ['coupon-offers'],
  forStore: (storeId) => [...couponOfferKeys.all, storeId],
}

/**
 * Signed-in customers only.
 * @param {string} storeId
 * @returns {Promise<CouponOffer[]>}
 */
export async function fetchCouponOffers(storeId) {
  return (await httpClient.get('/coupons', { params: { storeId } })).data
}
