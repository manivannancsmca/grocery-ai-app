import { httpClient } from '../../shared/api/httpClient'
import { cleanParams, segment } from '../../shared/api/types'

/** @typedef {import('../../shared/utils/foodType').FoodType} FoodType */

/** Cart and checkout preview (cart-service, role CUSTOMER — Phase 5 contract §2–3). */

/** @typedef {'GROCERY' | 'RESTAURANT'} StoreType */
/** @typedef {'PRODUCT' | 'MENU_ITEM'} CartItemType */

/** Issue codes (contract §2). Every code except PRICE_CHANGED blocks checkout. */
export const ISSUE_CODES = [
  'PRICE_CHANGED',
  'ITEM_UNAVAILABLE',
  'OUT_OF_STOCK',
  'INSUFFICIENT_STOCK',
  'STORE_UNAVAILABLE',
  'STORE_CLOSED',
  'NOT_ACCEPTING_ORDERS',
  'PRICING_UNAVAILABLE',
  'AVAILABILITY_UNKNOWN',
  'ADDRESS_REQUIRED',
  'ADDRESS_LOCATION_MISSING',
  'OUT_OF_DELIVERY_AREA',
  'BELOW_MINIMUM_ORDER',
  'EMPTY_CART',
  // Phase 12 §2: the applied coupon is no longer valid (blocking until it is removed or the cart is fixed).
  'COUPON_NOT_APPLICABLE',
]
/** @typedef {(typeof ISSUE_CODES)[number]} IssueCode */

/**
 * @typedef {object} CartStore
 * @property {string} id
 * @property {StoreType} type
 * @property {string} name
 */

/**
 * @typedef {object} CartLine
 * @property {string} itemId productId or menuItemId.
 * @property {CartItemType} type
 * @property {string} name
 * @property {string | null} unitLabel
 * @property {string | null} imageUrl
 * @property {FoodType | null} foodType
 * @property {number} quantity
 * @property {number | null} unitPrice Live selling price; null when it cannot be priced now.
 * @property {number | null} mrp Grocery only.
 * @property {number | null} lineTotal
 * @property {number} priceWhenAdded Unit price when the line was created or its quantity last changed.
 * @property {number | null} availableQuantity Grocery only: live available stock.
 * @property {IssueCode | null} issue The line's issue code, if any.
 */

/**
 * @typedef {object} CartIssue
 * @property {IssueCode} code
 * @property {string | null} itemId
 * @property {boolean} blocking
 * @property {string} message
 */

/** Why a coupon cannot be used (Phase 12 contract §2). */
export const COUPON_REASONS = [
  'COUPON_NOT_FOUND',
  'COUPON_PAUSED',
  'COUPON_NOT_STARTED',
  'COUPON_EXPIRED',
  'COUPON_WRONG_STORE',
  'COUPON_MIN_ORDER',
  'COUPON_USAGE_LIMIT',
  'COUPON_CUSTOMER_LIMIT',
]
/** @typedef {(typeof COUPON_REASONS)[number]} CouponReason */

/**
 * The coupon applied to the cart, as the checkout preview evaluates it now.
 * @typedef {object} CartCoupon
 * @property {string} code
 * @property {boolean} valid false: it stays visible with the blocking issue COUPON_NOT_APPLICABLE until removed or the cart is fixed.
 * @property {number} discount Off the item subtotal.
 * @property {number} deliveryFeeDiscount FREE_DELIVERY: the delivery fee taken off.
 * @property {CouponReason | (string & {}) | null} [reason]
 * @property {string | null} [message]
 * @property {number | null} [minOrderSubtotal] With COUPON_MIN_ORDER (optional).
 */

/**
 * @typedef {object} CartView
 * @property {CartStore | null} store null for an empty cart.
 * @property {CartLine[]} lines
 * @property {number} itemCount Sum of quantities.
 * @property {number} subtotal Sum of lineTotal of priced lines.
 * @property {number} mrpTotal
 * @property {number} savings
 * @property {string} currency
 * @property {CartIssue[]} issues
 * @property {string | null} updatedAt null for a cart that was never written.
 * @property {CartCoupon | null} [coupon] Phase 12: the applied coupon (always on the preview; the cart's own answers may carry it too).
 * @property {number} [discount] Phase 12: coupon discount off the subtotal (0 without one).
 * @property {number} [deliveryFeeDiscount] Phase 12: delivery fee taken off by a FREE_DELIVERY coupon (0 without one).
 */

/**
 * @typedef {object} CheckoutAddress
 * @property {string} id
 * @property {string} label
 * @property {string} recipientName
 * @property {string} phone
 * @property {string} line1
 * @property {string | null} line2
 * @property {string | null} landmark
 * @property {string} city
 * @property {string} state
 * @property {string} postalCode
 * @property {number | null} latitude
 * @property {number | null} longitude
 */

/**
 * @typedef {object} DeliveryQuote
 * @property {number} distanceKm
 * @property {number} fee
 * @property {number | null} freeAbove Subtotal above which delivery is free; null = never free (restaurants).
 * @property {number} maxDistanceKm
 * @property {number | null} minimumOrder null = no minimum (restaurants).
 */

/**
 * `GET /api/cart/preview` — CartView plus address, delivery fee and total.
 * @typedef {CartView & { address: CheckoutAddress | null, delivery: DeliveryQuote | null, total: number, canCheckout: boolean, pricedAt: string }} CheckoutPreview
 * - `delivery`: null when there is no usable address or the cart is empty.
 * - `total`: subtotal + delivery fee − discount − deliveryFeeDiscount (all prices include taxes).
 * - `canCheckout`: No blocking issue.
 */

/**
 * What to add — exactly one kind of item.
 * @typedef {(
 *   | { type: 'PRODUCT', productId: string }
 *   | { type: 'MENU_ITEM', restaurantId: string, menuItemId: string }
 * )} CartItemTarget
 */

/**
 * `AddItemRequest`; `replaceCart: true` empties a cart from another store first.
 * @typedef {CartItemTarget & { quantity: number, replaceCart?: boolean }} AddItemRequest
 */

/** 1–20 per line (`422 QUANTITY_LIMIT` above). */
export const MAX_LINE_QUANTITY = 20
/** At most 30 lines (`422 CART_LIMIT_REACHED`). */
export const MAX_CART_LINES = 30

export const CART_STORE_CONFLICT = 'CART_STORE_CONFLICT'
/** 422 on `PUT /api/cart/coupon` (with `reason` and, for COUPON_MIN_ORDER, `minOrderSubtotal`); 409 on placement. */
export const COUPON_NOT_APPLICABLE = 'COUPON_NOT_APPLICABLE'
/** Codes are stored upper-case: 4–20 letters, digits or dashes. */
export const COUPON_CODE_PATTERN = /^[A-Z0-9-]{4,20}$/

/**
 * The id a cart line carries for this target (productId or menuItemId).
 * @param {CartItemTarget} target
 * @returns {string}
 */
export function targetItemId(target) {
  return target.type === 'PRODUCT' ? target.productId : target.menuItemId
}

/**
 * @param {CartView | undefined} cart
 * @param {CartItemType} type
 * @param {string} itemId
 * @returns {CartLine | undefined}
 */
export function findLine(cart, type, itemId) {
  return cart?.lines.find((line) => line.type === type && line.itemId === itemId)
}

/**
 * @param [currency='INR']
 * @returns {CartView}
 */
export function emptyCart(currency = 'INR') {
  return {
    store: null,
    lines: [],
    itemCount: 0,
    subtotal: 0,
    mrpTotal: 0,
    savings: 0,
    currency,
    issues: [],
    updatedAt: new Date().toISOString(),
  }
}

export const cartKeys = {
  /** Everything cart-related; the cart itself is the single query `['cart']`. */
  all: ['cart'],
  cart: () => cartKeys.all,
  previews: () => [...cartKeys.all, 'preview'],
  preview: (addressId) => [...cartKeys.previews(), addressId],
  /** Mutation key of every write touching one item (so all its steppers know it is busy). */
  itemWrite: (itemId) => ['cart-write', itemId],
  clear: () => ['cart-write', '*clear*'],
  /** Phase 12: applying or removing the coupon. */
  couponWrite: () => ['cart-write', '*coupon*'],
}

/** @returns {Promise<CartView>} */
export async function fetchCart() {
  return (await httpClient.get('/cart')).data
}

/**
 * @param {AddItemRequest} body
 * @returns {Promise<CartView>}
 */
export async function addCartItem(body) {
  return (await httpClient.post('/cart/items', body)).data
}

/**
 * 0–20; 0 removes the line.
 * @param {string} itemId
 * @param {number} quantity
 * @returns {Promise<CartView>}
 */
export async function updateCartItem(itemId, quantity) {
  return (await httpClient.put(`/cart/items/${segment(itemId)}`, { quantity })).data
}

/**
 * @param {string} itemId
 * @returns {Promise<CartView>}
 */
export async function removeCartItem(itemId) {
  return (await httpClient.delete(`/cart/items/${segment(itemId)}`)).data
}

/** @returns {Promise<void>} */
export async function clearCart() {
  await httpClient.delete('/cart')
}

/**
 * Phase 12: validates the code (one coupon per cart; a new one replaces the old). 422 COUPON_NOT_APPLICABLE.
 * @param {string} code
 * @returns {Promise<CartView>}
 */
export async function applyCartCoupon(code) {
  return (await httpClient.put('/cart/coupon', { code })).data
}

/** @returns {Promise<CartView>} */
export async function removeCartCoupon() {
  return (await httpClient.delete('/cart/coupon')).data
}

/**
 * Without `addressId` the customer's default address is used.
 * @param {string | null} addressId
 * @returns {Promise<CheckoutPreview>}
 */
export async function fetchCheckoutPreview(addressId) {
  return (await httpClient.get('/cart/preview', { params: cleanParams({ addressId }) })).data
}
