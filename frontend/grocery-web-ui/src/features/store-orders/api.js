import { httpClient } from '../../shared/api/httpClient'
import { cleanParams, segment } from '../../shared/api/types'

/** @typedef {import('../../shared/api/types').PageResponse} PageResponse */
/** @typedef {import('../orders/api').CancellationReason} CancellationReason */
/** @typedef {import('../orders/api').CancelledBy} CancelledBy */

/**
 * A store's view of its orders (Phase 8 contract §2): vendor-service for GROCERY orders, restaurant-service for
 * RESTAURANT orders. Same API and shapes in both; only the path prefix and the defaults differ.
 */

/** @typedef {'VENDOR' | 'RESTAURANT'} StoreKind */

/** Phase 9 adds `PICKED_UP`: the delivery partner took the order (terminal success; it leaves the board). */
export const STORE_ORDER_STATUSES = [
  'NEW',
  'ACCEPTED',
  'PREPARING',
  'READY_FOR_PICKUP',
  'PICKED_UP',
  'REJECTED',
  'CANCELLED',
]
/** @typedef {(typeof STORE_ORDER_STATUSES)[number]} StoreOrderStatus */
/**
 * `ACTIVE` = NEW, ACCEPTED, PREPARING and READY_FOR_PICKUP (never PICKED_UP).
 * @typedef {StoreOrderStatus | 'ACTIVE'} StoreOrderStatusFilter
 */

/**
 * @param {unknown} value
 * @returns {value is StoreOrderStatus}
 */
export function isStoreOrderStatus(value) {
  return typeof value === 'string' && STORE_ORDER_STATUSES.includes(value)
}

/** What a store user may pick when rejecting. */
export const STORE_REJECT_REASONS = ['OUT_OF_STOCK', 'STORE_CLOSED', 'TOO_BUSY', 'OTHER']
/** @typedef {(typeof STORE_REJECT_REASONS)[number]} StoreRejectReason */
/**
 * NO_RESPONSE is set by the auto-reject at the deadline (`auto: true`).
 * @typedef {StoreRejectReason | 'NO_RESPONSE'} StoreRejectionReason
 */

/**
 * @typedef {object} StoreOrderItem
 * @property {string} itemId
 * @property {string} name
 * @property {number} quantity
 * @property {number} unitPrice
 * @property {number} lineTotal
 */

/**
 * Recipient name and a coarse area (landmark / line 2 or city) — never the phone or the full address.
 * @typedef {object} StoreOrderCustomer
 * @property {string | null} name
 * @property {string | null} area
 */

/**
 * @typedef {object} StoreOrderRejection
 * @property {StoreRejectionReason | (string & {})} reason
 * @property {string | null} note
 * @property {boolean} auto True when the deadline passed without an answer.
 * @property {string} at
 */

/**
 * @typedef {object} StoreOrderCancellation
 * @property {CancellationReason | (string & {})} reason
 * @property {CancelledBy | (string & {})} by
 * @property {string} at
 */

/**
 * @typedef {object} StoreOrderTimelineEntry
 * @property {StoreOrderStatus} status
 * @property {string} at
 */

/**
 * `StoreOrderView`
 * @typedef {object} StoreOrderView
 * @property {string} orderId
 * @property {string} orderNumber
 * @property {string} storeId
 * @property {StoreOrderStatus} status
 * @property {StoreOrderItem[]} items
 * @property {number} itemCount Sum of quantities.
 * @property {number} subtotal Item total — what the store sells (the delivery fee is not the store's).
 * @property {number} total
 * @property {string} currency
 * @property {StoreOrderCustomer | null} customer
 * @property {string | null} note The customer's note, or null.
 * @property {string} receivedAt
 * @property {string} acceptBy NEW orders not accepted by then are rejected automatically.
 * @property {string | null} acceptedAt
 * @property {number | null} readyInMinutes
 * @property {string | null} readyBy
 * @property {string | null} preparingAt
 * @property {string | null} readyAt
 * @property {string | null} [pickedUpAt] Phase 9: not in the contract's StoreOrderView; read when present, else the PICKED_UP timeline entry.
 * @property {StoreOrderRejection | null} rejection
 * @property {StoreOrderCancellation | null} cancellation
 * @property {StoreOrderTimelineEntry[]} timeline
 */

/**
 * @typedef {object} StoreOrderListParams
 * @property {StoreOrderStatusFilter} [status]
 * @property {number} [page]
 * @property {number} [size] ≤ 100.
 */

/**
 * @typedef {object} RejectStoreOrderRequest
 * @property {StoreRejectReason} reason
 * @property {string} [note] ≤ 300; required for OTHER.
 */

/** Limits from the contract. */
export const STORE_ORDER_LIMITS = { readyInMin: 5, readyInMax: 180, rejectNote: 300, pageSize: 100 }

/** The "Ready in" choices on the board (minutes). */
export const READY_IN_CHOICES = [10, 15, 20, 30, 45, 60]

export const STORE_ORDER_ERRORS = {
  notFound: 'STORE_ORDER_NOT_FOUND',
  /** Accept / reject of an order that is no longer NEW (property `status`). */
  notPending: 'STORE_ORDER_NOT_PENDING',
  /** Accept after `acceptBy`. */
  acceptanceExpired: 'ACCEPTANCE_EXPIRED',
  /** Preparing / ready from a status that does not allow it (property `status`). */
  invalidTransition: 'INVALID_STORE_ORDER_TRANSITION',
}

/**
 * @typedef {object} StoreKindConfig
 * @property {string} apiBase API path prefix (relative to `/api`).
 * @property {string} ordersPath The board and the reports in the app.
 * @property {string} reportsPath
 * @property {number} defaultReadyInMinutes Contract §2: vendor 15, restaurant 20.
 * @property {string} preparingLabel "Packing" for groceries, "Preparing" for restaurants.
 * @property {string} startPreparingLabel
 * @property {string} storeNoun
 */

/** @type {Record<StoreKind, StoreKindConfig>} */
export const STORE_KIND_CONFIG = {
  VENDOR: {
    apiBase: '/vendor',
    ordersPath: '/vendor/orders',
    reportsPath: '/vendor/reports',
    defaultReadyInMinutes: 15,
    preparingLabel: 'Packing',
    startPreparingLabel: 'Start packing',
    storeNoun: 'store',
  },
  RESTAURANT: {
    apiBase: '/restaurant',
    ordersPath: '/restaurant/orders',
    reportsPath: '/restaurant/reports',
    defaultReadyInMinutes: 20,
    preparingLabel: 'Preparing',
    startPreparingLabel: 'Start preparing',
    storeNoun: 'restaurant',
  },
}

export const storeOrderKeys = {
  all: ['store-orders'],
  ofKind: (kind) => [...storeOrderKeys.all, kind],
  lists: (kind) => [...storeOrderKeys.ofKind(kind), 'list'],
  list: (kind, params) => [...storeOrderKeys.lists(kind), params],
  /** PICKED_UP + REJECTED + CANCELLED orders closed today (India time). */
  closedToday: (kind, day) => [...storeOrderKeys.ofKind(kind), 'closed', day],
  /** Who collects the orders still at the store (delivery-service); refreshed with the board. */
  deliveries: (kind) => [...storeOrderKeys.ofKind(kind), 'deliveries'],
}

/**
 * The partner collecting an order: the store hands the order over to them and may call them.
 * @typedef {object} StoreDeliveryPartner
 * @property {string} name Full name.
 * @property {string | null} phone
 * @property {string | null} vehicleType BICYCLE, SCOOTER, MOTORCYCLE or CAR.
 * @property {string | null} vehicleNumber Null for a bicycle.
 */

/**
 * @typedef {object} StoreDeliveryLocation
 * @property {number} latitude
 * @property {number} longitude
 * @property {number | null} heading
 * @property {string} at When the partner's phone sent it; an old one means the phone stopped reporting.
 */

/**
 * `GET /api/delivery/store/deliveries` (docs/api/phase-9-delivery.md, store addendum): a delivery of an order still at
 * the store — PENDING (the store has not accepted yet), SEARCHING (looking for a partner) or ASSIGNED.
 * @typedef {object} StoreDelivery
 * @property {string} deliveryId
 * @property {string} orderId
 * @property {string} orderNumber
 * @property {'PENDING' | 'SEARCHING' | 'ASSIGNED' | (string & {})} status
 * @property {boolean} assignmentFailed SEARCHING and nobody was free within the largest radius yet (it keeps trying).
 * @property {string | null} assignedAt
 * @property {StoreDeliveryPartner | null} partner Null until a partner accepts.
 * @property {StoreDeliveryLocation | null} partnerLocation ASSIGNED with a known position only (also the two fields below).
 * @property {number | null} distanceToStoreKm Straight line, partner → store, 0.1 km.
 * @property {number | null} etaToStoreMinutes
 */

/**
 * The caller's store's deliveries still at the store, oldest first (at most 100).
 * @returns {Promise<StoreDelivery[]>}
 */
export async function fetchStoreDeliveries() {
  return (await httpClient.get('/delivery/store/deliveries')).data
}

/** @param {StoreKind} kind */
const ordersBase = (kind) => `${STORE_KIND_CONFIG[kind].apiBase}/orders`
/**
 * @param {StoreKind} kind
 * @param {string} orderId
 */
const orderPath = (kind, orderId) => `${ordersBase(kind)}/${segment(orderId)}`

/**
 * NEW first (by `acceptBy`, soonest first), then the rest newest first.
 * @param {StoreKind} kind
 * @param {StoreOrderListParams} params
 * @returns {Promise<PageResponse<StoreOrderView>>}
 */
export async function listStoreOrders(kind, params) {
  return (await httpClient.get(ordersBase(kind), { params: cleanParams(params) })).data
}

/**
 * Another store's order or an unknown id → 404 STORE_ORDER_NOT_FOUND.
 * @param {StoreKind} kind
 * @param {string} orderId
 * @returns {Promise<StoreOrderView>}
 */
export async function fetchStoreOrder(kind, orderId) {
  return (await httpClient.get(orderPath(kind, orderId))).data
}

/**
 * 409 STORE_ORDER_NOT_PENDING / ACCEPTANCE_EXPIRED; accepting an ACCEPTED order again → 200 unchanged.
 * @param {StoreKind} kind
 * @param {string} orderId
 * @param {number} readyInMinutes
 * @returns {Promise<StoreOrderView>}
 */
export async function acceptStoreOrder(kind, orderId, readyInMinutes) {
  return (await httpClient.post(`${orderPath(kind, orderId)}/accept`, { readyInMinutes })).data
}

/**
 * Only from NEW (409 STORE_ORDER_NOT_PENDING); rejecting again → 200 unchanged.
 * @param {StoreKind} kind
 * @param {string} orderId
 * @param {RejectStoreOrderRequest} body
 * @returns {Promise<StoreOrderView>}
 */
export async function rejectStoreOrder(kind, orderId, body) {
  return (await httpClient.post(`${orderPath(kind, orderId)}/reject`, body)).data
}

/**
 * From ACCEPTED; 409 INVALID_STORE_ORDER_TRANSITION otherwise.
 * @param {StoreKind} kind
 * @param {string} orderId
 * @returns {Promise<StoreOrderView>}
 */
export async function markStoreOrderPreparing(kind, orderId) {
  return (await httpClient.post(`${orderPath(kind, orderId)}/preparing`)).data
}

/**
 * From ACCEPTED or PREPARING; 409 INVALID_STORE_ORDER_TRANSITION otherwise.
 * @param {StoreKind} kind
 * @param {string} orderId
 * @returns {Promise<StoreOrderView>}
 */
export async function markStoreOrderReady(kind, orderId) {
  return (await httpClient.post(`${orderPath(kind, orderId)}/ready`)).data
}
