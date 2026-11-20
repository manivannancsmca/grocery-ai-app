import { httpClient } from '../../shared/api/httpClient'
import { cleanParams, segment } from '../../shared/api/types'
/** @typedef {import('../../shared/api/types').PageResponse} PageResponse */
/** @typedef {import('../../shared/utils/foodType').FoodType} FoodType */
/** @typedef {import('../cart/api').CartItemType} CartItemType */
/** @typedef {import('../cart/api').CartStore} CartStore */
/** @typedef {import('../cart/api').StoreType} StoreType */
/** @typedef {import('../returns/api').OrderReturnSummary} OrderReturnSummary */

/** Orders (order-service — Phase 6 contract §2 and §4; Phase 8 contract §4 adds the store's fulfilment steps). */

export const ORDER_STATUSES = [
  'CREATED',
  'AWAITING_PAYMENT',
  'CONFIRMED',
  // Phase 8: the store accepted it, is preparing / packing it, and it is ready for a delivery partner.
  'ACCEPTED',
  'PREPARING',
  'READY_FOR_PICKUP',
  // Phase 9: the partner picked it up, delivered it, or could not deliver it (admins decide on a refund).
  'OUT_FOR_DELIVERY',
  'DELIVERED',
  'DELIVERY_FAILED',
  'REJECTED',
  'CANCELLED',
]
/** @typedef {(typeof ORDER_STATUSES)[number]} OrderStatus */
/**
 * `GET /api/orders?status=` also accepts `OPEN` (CREATED + AWAITING_PAYMENT).
 * @typedef {OrderStatus | 'OPEN'} OrderStatusFilter
 */

/** @typedef {StoreType} OrderType */
/** @type {readonly OrderType[]} */
export const ORDER_TYPES = ['GROCERY', 'RESTAURANT']

/**
 * @param {unknown} value
 * @returns {value is OrderStatus}
 */
export function isOrderStatus(value) {
  return typeof value === 'string' && ORDER_STATUSES.includes(value)
}

/**
 * @param {unknown} value
 * @returns {value is OrderType}
 */
export function isOrderType(value) {
  return typeof value === 'string' && ORDER_TYPES.includes(value)
}

/**
 * order-service's own view of the payment, kept from payment events.
 * @typedef {(
 *   | 'NOT_STARTED'
 *   | 'PENDING'
 *   | 'PAID'
 *   | 'EXPIRED'
 *   | 'NOT_REQUIRED'
 *   | 'REFUND_PENDING'
 *   | 'REFUNDED'
 *   | 'PARTIALLY_REFUNDED'
 * )} OrderPaymentStatus
 */

/**
 * @typedef {(
 *   | 'CUSTOMER_CANCELLED'
 *   | 'ADMIN_CANCELLED'
 *   | 'PAYMENT_TIMEOUT'
 *   | 'RESERVATION_EXPIRED'
 *   | 'STORE_REJECTED'
 *   | 'STORE_NO_RESPONSE'
 * )} CancellationReason
 */
/** @typedef {'CUSTOMER' | 'ADMIN' | 'SYSTEM' | 'STORE'} CancelledBy */

/**
 * @typedef {object} OrderItem
 * @property {string} itemId
 * @property {CartItemType} type
 * @property {string} name
 * @property {string | null} unitLabel
 * @property {string | null} imageUrl
 * @property {FoodType | null} foodType
 * @property {number} quantity
 * @property {number} unitPrice
 * @property {number | null} mrp Grocery only.
 * @property {number} lineTotal
 */

/**
 * Snapshot of the delivery address taken when the order was placed.
 * @typedef {object} OrderAddress
 * @property {string | null} label
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
 * @typedef {object} PaymentFailure
 * @property {string} code
 * @property {string} description
 * @property {string} at
 */

/**
 * @typedef {object} OrderPayment
 * @property {OrderPaymentStatus} status
 * @property {string | null} dueAt End of the payment window (15 minutes from creation).
 * @property {string | null} paidAt
 * @property {number} refundedAmount
 * @property {PaymentFailure | null} lastFailure Most recent declined attempt while the order is still payable.
 */

/**
 * @typedef {object} OrderCancellation
 * @property {CancellationReason} reason
 * @property {string | null} note
 * @property {CancelledBy} by
 * @property {string} at
 */

/**
 * @typedef {object} UnavailableItem
 * @property {string} itemId
 * @property {string} name
 * @property {number} requested
 * @property {number} available
 */

/**
 * @typedef {object} OrderRejection
 * @property {'OUT_OF_STOCK' | (string & {})} reason
 * @property {UnavailableItem[]} unavailableItems
 */

/**
 * @typedef {object} OrderTimelineEntry
 * @property {OrderStatus} status
 * @property {string} at
 * @property {string | null} reason
 */

/**
 * Phase 9: the delivery partner as order-service records it (first name only for customers).
 * @typedef {object} OrderDelivery
 * @property {string | null} partnerName
 * @property {string | null} assignedAt
 * @property {string | null} pickedUpAt
 * @property {string | null} deliveredAt
 */

/**
 * @typedef {object} OrderView
 * @property {string} id
 * @property {string} orderNumber Short and unique, shown to people (e.g. `GA-7K3F9Q2M`).
 * @property {OrderType} type
 * @property {OrderStatus} status
 * @property {CartStore} store
 * @property {OrderItem[]} items
 * @property {number} itemCount Sum of quantities.
 * @property {number} subtotal
 * @property {number} mrpTotal
 * @property {number} savings
 * @property {number} deliveryFee
 * @property {number} total
 * @property {string} currency
 * @property {OrderAddress} deliveryAddress
 * @property {number | null} deliveryDistanceKm
 * @property {string | null} note
 * @property {OrderPayment} payment
 * @property {boolean} cancellable Customer cancel: only until the store accepts (CREATED, AWAITING_PAYMENT, CONFIRMED).
 * @property {OrderCancellation | null} cancellation
 * @property {OrderRejection | null} rejection
 * @property {string | null} [acceptedAt] Phase 8: set when the store accepts (null before; absent from older answers).
 * @property {number | null} [readyInMinutes] Phase 8: the preparation time the store promised, in minutes.
 * @property {string | null} [readyBy] Phase 8: `acceptedAt + readyInMinutes`.
 * @property {OrderDelivery | null} [delivery] Phase 9: null until a partner accepts; absent from older answers.
 * @property {boolean} [trackable] Phase 9: live tracking applies (from ACCEPTED to OUT_FOR_DELIVERY).
 * @property {string | null} [couponCode] Phase 12: the coupon redeemed for this order (null / absent without one).
 * @property {number | null} [discount] Phase 12: coupon discount off the subtotal (`total` already has it taken off).
 * @property {number | null} [deliveryFeeDiscount] Phase 12: delivery fee taken off by a FREE_DELIVERY coupon.
 * @property {boolean} [returnable] Phase 12: the customer may request a return now (DELIVERED, within the return window — a platform setting, 48 h by default — no open request, something left).
 * @property {OrderReturnSummary[]} [returns] Phase 12: the order's return requests.
 * @property {OrderTimelineEntry[]} timeline Oldest first.
 * @property {string} createdAt
 * @property {string} updatedAt
 */

/**
 * The payment as the lists give it.
 * @typedef {object} OrderSummaryPayment
 * @property {OrderPaymentStatus} status
 * @property {string | null} [dueAt] End of the payment window (`OrderPayment.dueAt`), only while the order is AWAITING_PAYMENT (null otherwise). Absent from older answers.
 */

/**
 * @typedef {object} OrderSummary
 * @property {string} id
 * @property {string} orderNumber
 * @property {OrderType} type
 * @property {OrderStatus} status
 * @property {CartStore} store
 * @property {number} itemCount
 * @property {number} total
 * @property {string} currency
 * @property {string} firstItemName
 * @property {OrderSummaryPayment} payment
 * @property {string} createdAt
 * @property {string | null} [couponCode] Phase 12.
 * @property {number | null} [discount]
 * @property {number | null} [deliveryFeeDiscount]
 */

/**
 * @typedef {object} PlaceOrderRequest
 * @property {string} [addressId] Absent → the default address (same rule as the checkout preview).
 * @property {number} expectedTotal The total the customer saw; must equal the live total (409 TOTAL_CHANGED otherwise).
 * @property {string} [note] Delivery instructions, ≤ 300.
 */

/** Limits from the contract. */
export const ORDER_LIMITS = { note: 300, cancelReason: 300 }

/** Error codes of the order endpoints. */
export const ORDER_ERRORS = {
  checkoutBlocked: 'CHECKOUT_BLOCKED',
  totalChanged: 'TOTAL_CHANGED',
  dependencyUnavailable: 'DEPENDENCY_UNAVAILABLE',
  keyReused: 'IDEMPOTENCY_KEY_REUSED',
  addressNotFound: 'ADDRESS_NOT_FOUND',
  notFound: 'ORDER_NOT_FOUND',
  notCancellable: 'ORDER_NOT_CANCELLABLE',
  /** Phase 12: redeeming the cart's coupon failed (`reason`); no order was placed. */
  couponNotApplicable: 'COUPON_NOT_APPLICABLE',
}

export const IDEMPOTENCY_KEY_HEADER = 'Idempotency-Key'

/**
 * @typedef {object} MyOrderListParams
 * @property {OrderStatusFilter} [status]
 * @property {number} [page]
 * @property {number} [size]
 */

export const orderKeys = {
  all: ['orders'],
  lists: () => [...orderKeys.all, 'list'],
  list: (params) => [...orderKeys.lists(), params],
  detail: (id) => [...orderKeys.all, 'detail', id],
}

/** @param {string} id */
const item = (id) => `/orders/${segment(id)}`

/**
 * `201 OrderView`; a replay of the same key + body answers 201 with the original order.
 * @param {PlaceOrderRequest} body
 * @param {string} idempotencyKey
 * @returns {Promise<OrderView>}
 */
export async function placeOrder(body, idempotencyKey) {
  return (await httpClient.post('/orders', body, { headers: { [IDEMPOTENCY_KEY_HEADER]: idempotencyKey } })).data
}

/**
 * Newest first.
 * @param {MyOrderListParams} params
 * @returns {Promise<PageResponse<OrderSummary>>}
 */
export async function listMyOrders(params) {
  return (await httpClient.get('/orders', { params: cleanParams(params) })).data
}

/**
 * Another customer's order → 404 ORDER_NOT_FOUND.
 * @param {string} id
 * @returns {Promise<OrderView>}
 */
export async function fetchOrder(id) {
  return (await httpClient.get(item(id))).data
}

/**
 * Idempotent; 409 ORDER_NOT_CANCELLABLE. `reason` optional (≤ 300).
 * @param {string} id
 * @param {string | null} reason
 * @returns {Promise<OrderView>}
 */
export async function cancelOrder(id, reason) {
  return (await httpClient.post(`${item(id)}/cancel`, reason ? { reason } : {})).data
}
