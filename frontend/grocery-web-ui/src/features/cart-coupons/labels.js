import { formatPrice } from '../../shared/utils/format'

/** @typedef {import('./api').CouponOffer} CouponOffer */

/**
 * `₹` amounts are kept to paise so `199 − 147.9` reads `₹51.10`.
 * @param {number} minimum
 * @param {number} subtotal
 * @returns {number}
 */
function shortBy(minimum, subtotal) {
  return Math.round((minimum - subtotal) * 100) / 100
}

/**
 * @typedef {object} CouponReasonContext
 * @property {string | null} [code] The code the customer typed or applied.
 * @property {number | null} [minOrderSubtotal] For COUPON_MIN_ORDER.
 * @property {number | null} [subtotal]
 * @property {string | null} [storeName]
 */

/**
 * Why a coupon cannot be used, in one sentence (Phase 12 §2 reasons: not found, paused, not started, expired, wrong
 * store, minimum order, usage limits). Null for a reason this app does not know.
 * @param {string | null | undefined} reason
 * @param {CouponReasonContext} [context={}]
 * @returns {string | null}
 */
export function couponReasonText(reason, context = {}) {
  const code = context.code ? context.code.toUpperCase() : null
  switch (reason) {
    case 'COUPON_NOT_FOUND':
      return code ? `There is no coupon with the code ${code}. Check the spelling.` : 'This coupon does not exist.'
    case 'COUPON_PAUSED':
      return 'This coupon is paused at the moment. Try again later.'
    case 'COUPON_NOT_STARTED':
      return 'This coupon is not valid yet.'
    case 'COUPON_EXPIRED':
      return 'This coupon has expired.'
    case 'COUPON_WRONG_STORE':
      return context.storeName
        ? `This coupon can’t be used at ${context.storeName}.`
        : 'This coupon can’t be used at this store.'
    case 'COUPON_MIN_ORDER': {
      const minimum = context.minOrderSubtotal
      if (minimum === null || minimum === undefined) return 'Your order is below this coupon’s minimum order value.'
      const missing =
        context.subtotal !== null && context.subtotal !== undefined ? shortBy(minimum, context.subtotal) : null
      return missing !== null && missing > 0
        ? `This coupon needs a minimum order of ${formatPrice(minimum)}. Add ${formatPrice(missing)} more to use it.`
        : `This coupon needs a minimum order of ${formatPrice(minimum)}.`
    }
    case 'COUPON_USAGE_LIMIT':
      return 'This coupon has been fully used up.'
    case 'COUPON_CUSTOMER_LIMIT':
      return 'You have already used this coupon as many times as allowed.'
    default:
      return null
  }
}

/**
 * What the customer saves with a valid coupon, in words.
 * @param {{ discount: number, deliveryFeeDiscount: number }} coupon
 * @returns {string}
 */
export function couponSavingsText(coupon) {
  if (coupon.discount > 0 && coupon.deliveryFeeDiscount > 0) {
    return `You save ${formatPrice(coupon.discount)} off your items, and delivery is free.`
  }
  if (coupon.discount > 0) return `You save ${formatPrice(coupon.discount)} off your items.`
  if (coupon.deliveryFeeDiscount > 0) return `Delivery is free — you save ${formatPrice(coupon.deliveryFeeDiscount)}.`
  return 'It applies to this order.'
}

/**
 * "10% off up to ₹100", "₹50 off", "Free delivery".
 * @param {Pick<CouponOffer, 'discountType' | 'value' | 'maxDiscount'>} offer
 * @returns {string}
 */
export function offerBenefit(offer) {
  switch (offer.discountType) {
    case 'PERCENT':
      return `${offer.value ?? 0}% off${offer.maxDiscount ? ` up to ${formatPrice(offer.maxDiscount)}` : ''}`
    case 'FLAT':
      return `${formatPrice(offer.value ?? 0)} off`
    case 'FREE_DELIVERY':
      return 'Free delivery'
    default:
      return 'Discount'
  }
}

/**
 * Upper-cased and trimmed, as pricing-service stores codes.
 * @param {string} value
 * @returns {string}
 */
export function normalizeCouponCode(value) {
  return value.trim().toUpperCase()
}
