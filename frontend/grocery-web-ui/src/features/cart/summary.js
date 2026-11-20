import { cartLevelIssues, issueText, lineIssue, roundMoney } from './issues'

/** @typedef {import('./api').CartCoupon} CartCoupon */
/** @typedef {import('./api').CartIssue} CartIssue */
/** @typedef {import('./api').CartLine} CartLine */
/** @typedef {import('./api').CartView} CartView */
/** @typedef {import('./api').CheckoutPreview} CheckoutPreview */
/** @typedef {import('./api').DeliveryQuote} DeliveryQuote */

/** Below this many in stock, a line says "Only 3 left" (it also explains a "+" that stopped at the stock). */
export const LOW_STOCK_THRESHOLD = 5

/** @param {number} value */
const clampPercent = (value) => Math.min(100, Math.max(4, value))

/**
 * How far the items are from free delivery (grocery stores with a threshold), or that it is reached.
 * @typedef {(
 *   | { kind: 'short', shortfall: number, freeAbove: number, percent: number }
 *   | { kind: 'reached', freeAbove: number }
 * )} FreeDeliveryProgress
 */

/**
 * Free delivery for this subtotal: "add ₹X more" while a fee is charged below the threshold, "reached" once the fee
 * is waived by it. Null without a threshold (restaurants), without a quote, or when a coupon already pays the fee.
 * @param {number} subtotal
 * @param {DeliveryQuote | null | undefined} delivery
 * @param [deliveryFeeDiscount=0]
 * @returns {FreeDeliveryProgress | null}
 */
export function freeDeliveryProgress(subtotal, delivery, deliveryFeeDiscount = 0) {
  if (!delivery || delivery.freeAbove === null) return null
  const missing = roundMoney(delivery.freeAbove - subtotal)
  if (missing > 0) {
    if (delivery.fee <= 0 || deliveryFeeDiscount >= delivery.fee) return null
    return {
      kind: 'short',
      shortfall: missing,
      freeAbove: delivery.freeAbove,
      percent: clampPercent((subtotal / delivery.freeAbove) * 100),
    }
  }
  return delivery.fee === 0 ? { kind: 'reached', freeAbove: delivery.freeAbove } : null
}

/**
 * @typedef {object} MinimumOrderGap
 * @property {number} minimum
 * @property {number} shortfall
 * @property {number} percent
 */

/**
 * How much is missing to reach the store's minimum order, or null when there is none or it is reached.
 * @param {number} subtotal
 * @param {DeliveryQuote | null | undefined} delivery
 * @returns {MinimumOrderGap | null}
 */
export function minimumOrderGap(subtotal, delivery) {
  const minimum = delivery?.minimumOrder
  if (minimum === null || minimum === undefined || minimum <= 0) return null
  const missing = roundMoney(minimum - subtotal)
  return missing > 0 ? { minimum, shortfall: missing, percent: clampPercent((subtotal / minimum) * 100) } : null
}

/**
 * What the line saves against its MRP (grocery), in rupees.
 * @param {Pick<CartLine, 'mrp' | 'unitPrice' | 'quantity'>} line
 * @returns {number}
 */
export function lineSaving(line) {
  if (line.mrp === null || line.unitPrice === null || line.mrp <= line.unitPrice) return 0
  return roundMoney((line.mrp - line.unitPrice) * line.quantity)
}

/**
 * "18" for 18 % off the MRP; 0 without a discount.
 * @param {Pick<CartLine, 'mrp' | 'unitPrice'>} line
 * @returns {number}
 */
export function discountPercent(line) {
  if (line.mrp === null || line.unitPrice === null || line.mrp <= line.unitPrice) return 0
  return Math.round(((line.mrp - line.unitPrice) / line.mrp) * 100)
}

/** @param {CartIssue} issue */
const issueKey = (issue) => `${issue.code}-${issue.itemId ?? 'cart'}`

/**
 * Every issue of the cart page, without repeats: the cart's own (lines and store; fresh after every write) and, from
 * the checkout preview, the ones that are not about a line (address, delivery area, minimum order, coupon). A preview
 * still being re-fetched after a write may talk about lines that changed or went meanwhile, so its line issues are not
 * used.
 * @param {Pick<CartView, 'issues' | 'lines'>} cart
 * @param {Pick<CheckoutPreview, 'issues'> | null} [preview]
 * @returns {CartIssue[]}
 */
export function cartPageIssues(cart, preview) {
  const fromLines = cart.lines.map((line) => lineIssue(cart, line)).filter((issue) => issue !== null)
  const all = [
    ...fromLines,
    ...cartLevelIssues(cart),
    ...(preview ? preview.issues.filter((issue) => !issue.itemId) : []),
  ]
  const seen = new Set()
  return all.filter((issue) => {
    const key = issueKey(issue)
    if (seen.has(key)) return false
    seen.add(key)
    return true
  })
}

/**
 * Store-wide reasons the cart cannot be ordered now (shown as a pill next to the store's name).
 * @param {readonly CartIssue[]} issues
 * @returns {string | null}
 */
export function storeStatusLabel(issues) {
  const codes = new Set(issues.filter((issue) => !issue.itemId).map((issue) => issue.code))
  if (codes.has('STORE_UNAVAILABLE')) return 'Unavailable'
  if (codes.has('STORE_CLOSED')) return 'Closed now'
  if (codes.has('NOT_ACCEPTING_ORDERS')) return 'Paused new orders'
  return null
}

/**
 * Issues that clear up on their own (a check that could not run): worth a "Check again".
 * @param {readonly CartIssue[]} issues
 * @returns {boolean}
 */
export function hasTransientIssue(issues) {
  return issues.some((issue) => issue.code === 'PRICING_UNAVAILABLE' || issue.code === 'AVAILABILITY_UNKNOWN')
}

/**
 * What the live region reads after a re-check: every issue as one sentence each ("Tomato now costs ₹50 (was ₹90).
 * Onion is sold out. …"), or that everything is fine.
 * @param {readonly CartIssue[]} issues
 * @param {Parameters<typeof issueText>[1]} context
 * @returns {string}
 */
export function issuesAnnouncement(issues, context) {
  if (issues.length === 0) return 'Prices and stock checked: everything in your cart is available.'
  return `Prices and stock checked. ${issues.map((issue) => issueText(issue, context)).join(' ')}`
}

/**
 * The cart page's bill: from the checkout preview (with the delivery fee) or, until it is there, from the cart.
 * @typedef {object} CartBill
 * @property {number} itemCount
 * @property {number} subtotal
 * @property {number} mrpTotal
 * @property {DeliveryQuote | null} delivery null without a preview or a usable address.
 * @property {CartCoupon | null} coupon The coupon whose discounts are in the bill (its code names the rows).
 * @property {number} discount
 * @property {number} deliveryFeeDiscount
 * @property {number} total
 * @property {boolean} complete The total includes the delivery fee (a preview with a quote).
 * @property {number} saved MRP savings, the coupon and a waived delivery fee together.
 * @property {MinimumOrderGap | null} minimum
 * @property {FreeDeliveryProgress | null} freeDelivery Not shown while the minimum order is not reached (one nudge at a time).
 */

/**
 * @param {CartView} cart
 * @param {CheckoutPreview | null | undefined} preview
 * @param {CartCoupon | null} coupon
 * @returns {CartBill}
 */
export function cartBill(cart, preview, coupon) {
  const source = preview ?? cart
  const valid = coupon?.valid ? coupon : null
  const discount = preview?.discount ?? valid?.discount ?? 0
  const deliveryFeeDiscount = preview ? (preview.deliveryFeeDiscount ?? valid?.deliveryFeeDiscount ?? 0) : 0
  const delivery = preview?.delivery ?? null
  // No nudge towards free delivery to an address the store does not deliver to.
  const undeliverable =
    preview?.issues.some(
      (issue) => issue.code === 'OUT_OF_DELIVERY_AREA' || issue.code === 'ADDRESS_LOCATION_MISSING',
    ) ?? false
  const minimum = minimumOrderGap(source.subtotal, delivery)
  return {
    itemCount: source.itemCount,
    subtotal: source.subtotal,
    mrpTotal: source.mrpTotal,
    delivery,
    coupon,
    discount,
    deliveryFeeDiscount,
    total: preview ? preview.total : roundMoney(cart.subtotal - discount),
    complete: Boolean(preview?.delivery),
    saved: roundMoney(source.savings + discount + deliveryFeeDiscount),
    minimum,
    freeDelivery:
      minimum || undeliverable ? null : freeDeliveryProgress(source.subtotal, delivery, deliveryFeeDiscount),
  }
}

/**
 * The coupon to show: whether there is one comes from the cart (fresher after a write), its details from the preview
 * when it is about the same code (the preview also knows the delivery-fee discount).
 * @param {CartCoupon | null | undefined} cartCoupon
 * @param {CartCoupon | null | undefined} previewCoupon
 * @returns {CartCoupon | null}
 */
export function currentCoupon(cartCoupon, previewCoupon) {
  if (cartCoupon === undefined) return previewCoupon ?? null
  if (cartCoupon === null) return null
  return previewCoupon && previewCoupon.code === cartCoupon.code ? previewCoupon : cartCoupon
}
