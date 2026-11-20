import { httpClient } from '../../shared/api/httpClient'
import { toPage } from '../../shared/api/pages'
import { cleanParams, segment } from '../../shared/api/types'

/** @typedef {import('../../shared/api/types').PageResponse} PageResponse */

/**
 * Promotions & coupons (pricing-service — Phase 12 contract §2). Admins manage PLATFORM coupons (funded by the
 * platform; optionally for one store type) and may create STORE coupons for a store; a vendor or restaurant owner
 * manages its own STORE coupons (funded by the store). One coupon per cart.
 */

/**
 * Who manages: the admin area, a vendor or a restaurant owner — each under its own path.
 * @typedef {'admin' | 'vendor' | 'restaurant'} CouponOwner
 */

export const COUPON_SCOPES = ['PLATFORM', 'STORE']
/** @typedef {(typeof COUPON_SCOPES)[number]} CouponScope */

export const DISCOUNT_TYPES = ['PERCENT', 'FLAT', 'FREE_DELIVERY']
/** @typedef {(typeof DISCOUNT_TYPES)[number]} DiscountType */

export const COUPON_STORE_TYPES = ['ANY', 'GROCERY', 'RESTAURANT']
/** @typedef {(typeof COUPON_STORE_TYPES)[number]} CouponStoreType */

export const COUPON_CODE_PATTERN = /^[A-Z0-9-]{4,20}$/

/** Limits from the contract. */
export const COUPON_LIMITS = {
  name: 80,
  description: 300,
  percentMin: 1,
  percentMax: 90,
  /** A sane cap for flat amounts and limits (the contract only says `value ≤ subtotal` at use). */
  amountMax: 100_000,
  usageMax: 1_000_000,
}

export const COUPON_ERRORS = { codeTaken: 'COUPON_CODE_TAKEN' }

/**
 * `CouponView`. Times are instants (UTC).
 * @typedef {object} CouponView
 * @property {string} id
 * @property {string} code
 * @property {string} name
 * @property {string | null} description
 * @property {CouponScope | (string & {})} scope
 * @property {string | null} storeId STORE coupons: the vendor or restaurant.
 * @property {string | null} [storeName]
 * @property {CouponStoreType | (string & {}) | null} storeType PLATFORM coupons: which stores (ANY when not limited).
 * @property {DiscountType | (string & {})} discountType
 * @property {number | null} value PERCENT: 1–90; FLAT: rupees; FREE_DELIVERY: null / 0.
 * @property {number | null} maxDiscount PERCENT only.
 * @property {number} minOrderSubtotal
 * @property {string} validFrom
 * @property {string} validUntil
 * @property {number | null} totalUsageLimit
 * @property {number} perCustomerLimit
 * @property {boolean} active Paused coupons are not usable.
 * @property {boolean} public Listed to customers as an offer.
 * @property {number} usedCount RESERVED and CONFIRMED uses.
 * @property {number} discountGiven Discount (and delivery-fee discount) given so far, rupees.
 * @property {string} [createdAt]
 * @property {string} [updatedAt]
 */

/**
 * `CouponRequest`. Store owners' `scope` / `storeId` are forced by the server.
 * @typedef {object} CouponRequest
 * @property {string} code
 * @property {string} name
 * @property {string | null} description
 * @property {CouponScope} scope
 * @property {string | null} [storeId]
 * @property {CouponStoreType | null} [storeType]
 * @property {DiscountType} discountType
 * @property {number | null} value
 * @property {number | null} maxDiscount
 * @property {number} minOrderSubtotal
 * @property {string} validFrom
 * @property {string} validUntil
 * @property {number | null} totalUsageLimit
 * @property {number} perCustomerLimit
 * @property {boolean} public
 */

export const REDEMPTION_STATUSES = ['RESERVED', 'CONFIRMED', 'RELEASED']

/**
 * One use of a coupon (one order).
 * @typedef {object} CouponRedemptionView
 * @property {string} [id]
 * @property {string} orderId
 * @property {string | null} [orderNumber]
 * @property {string | null} [customerId]
 * @property {number} discount
 * @property {number} deliveryFeeDiscount
 * @property {(typeof REDEMPTION_STATUSES)[number] | (string & {})} status
 * @property {string | null} [createdAt]
 */

/**
 * `GET …/coupons/{id}/usage`, normalised: the totals (when sent) and the uses.
 * @typedef {object} CouponUsage
 * @property {number | null} usedCount
 * @property {number | null} discountGiven
 * @property {PageResponse<CouponRedemptionView>} redemptions
 */

/**
 * @typedef {object} CouponListParams
 * @property {string} [q]
 * @property {number} [page]
 * @property {number} [size]
 */

export const couponAdminKeys = {
  all: ['coupon-admin'],
  owner: (owner) => [...couponAdminKeys.all, owner],
  list: (owner, params) => [...couponAdminKeys.owner(owner), 'list', params],
  usage: (owner, id, page) => [...couponAdminKeys.owner(owner), 'usage', id, page],
}

/** @type {Record<CouponOwner, string>} */
const BASES = { admin: '/admin/coupons', vendor: '/vendor/coupons', restaurant: '/restaurant/coupons' }
/**
 * @param {CouponOwner} owner
 * @param {string} id
 */
const item = (owner, id) => `${BASES[owner]}/${segment(id)}`

/**
 * @param {CouponOwner} owner
 * @param {CouponListParams} params
 * @returns {Promise<PageResponse<CouponView>>}
 */
export async function listCoupons(owner, params) {
  return toPage((await httpClient.get(BASES[owner], { params: cleanParams(params) })).data)
}

/**
 * 409 COUPON_CODE_TAKEN.
 * @param {CouponOwner} owner
 * @param {CouponRequest} body
 * @returns {Promise<CouponView>}
 */
export async function createCoupon(owner, body) {
  return (await httpClient.post(BASES[owner], body)).data
}

/**
 * 409 COUPON_CODE_TAKEN.
 * @param {CouponOwner} owner
 * @param {string} id
 * @param {CouponRequest} body
 * @returns {Promise<CouponView>}
 */
export async function updateCoupon(owner, id, body) {
  return (await httpClient.put(item(owner, id), body)).data
}

/**
 * @param {CouponOwner} owner
 * @param {string} id
 * @param {boolean} active
 * @returns {Promise<CouponView>}
 */
export async function setCouponActive(owner, id, active) {
  return (await httpClient.post(`${item(owner, id)}/${active ? 'resume' : 'pause'}`)).data
}

/**
 * @typedef {(
 *   | PageResponse<CouponRedemptionView>
 *   | CouponRedemptionView[]
 *   | { usedCount?: number, discountGiven?: number, redemptions?: PageResponse<CouponRedemptionView> | CouponRedemptionView[] }
 * )} UsageAnswer
 */

/**
 * @param {CouponOwner} owner
 * @param {string} id
 * @param {number} page
 * @returns {Promise<CouponUsage>}
 */
export async function fetchCouponUsage(owner, id, page) {
  const data = (await httpClient.get(`${item(owner, id)}/usage`, { params: { page, size: 20 } })).data
  if (Array.isArray(data) || (data && 'content' in data)) {
    return { usedCount: null, discountGiven: null, redemptions: toPage(data) }
  }
  const summary = data ?? {}
  return {
    usedCount: summary.usedCount ?? null,
    discountGiven: summary.discountGiven ?? null,
    redemptions: toPage(summary.redemptions),
  }
}
