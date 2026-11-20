import { httpClient } from '../../shared/api/httpClient'
import { cleanParams, segment } from '../../shared/api/types'

/** @typedef {import('../../shared/api/types').PageResponse} PageResponse */
/** @typedef {import('../orders/api').OrderType} OrderType */

/**
 * Deliveries (delivery-service — Phase 9 contract §2–§3). The partner's own work: availability, live location,
 * offers, the active delivery and its steps, and history. Role DELIVERY_PARTNER; the partner id is the user id.
 * A partner who is not APPROVED gets 403 PARTNER_NOT_ACTIVE on everything here.
 */

export const DELIVERY_STATUSES = ['PENDING', 'SEARCHING', 'ASSIGNED', 'PICKED_UP', 'DELIVERED', 'FAILED', 'CANCELLED']
/** @typedef {(typeof DELIVERY_STATUSES)[number]} DeliveryStatus */

/**
 * @param {unknown} value
 * @returns {value is DeliveryStatus}
 */
export function isDeliveryStatus(value) {
  return typeof value === 'string' && DELIVERY_STATUSES.includes(value)
}

/** Why a partner could not deliver (`OTHER` needs a note). */
export const DELIVERY_FAIL_REASONS = ['CUSTOMER_UNREACHABLE', 'ADDRESS_NOT_FOUND', 'CUSTOMER_REFUSED', 'OTHER']
/** @typedef {(typeof DELIVERY_FAIL_REASONS)[number]} DeliveryFailReason */

/**
 * Where the server last saw the partner.
 * @typedef {object} PartnerLocation
 * @property {number} latitude
 * @property {number} longitude
 * @property {string} at
 */

/**
 * `PartnerStatusView`
 * @typedef {object} PartnerStatusView
 * @property {string} partnerId
 * @property {boolean} online
 * @property {PartnerLocation | null} location
 * @property {string | null} activeDeliveryId
 * @property {number} pendingOffers
 */

/**
 * @typedef {object} OfferStore
 * @property {string} name
 * @property {string | null} city
 * @property {number | null} latitude
 * @property {number | null} longitude
 */

/**
 * `OfferView` — before accepting, the partner sees no customer details.
 * @typedef {object} OfferView
 * @property {string} offerId
 * @property {string} deliveryId
 * @property {string} orderNumber
 * @property {OrderType} orderType
 * @property {OfferStore} store
 * @property {string | null} dropArea Landmark / line 2 + city, like the store board.
 * @property {number | null} distanceKm Partner → store when the offer was made.
 * @property {number | null} tripKm Store → customer.
 * @property {number} itemCount
 * @property {string} expiresAt
 */

/**
 * @typedef {object} DeliveryPickup
 * @property {string} name
 * @property {string | null} city
 * @property {number | null} latitude
 * @property {number | null} longitude
 */

/**
 * @typedef {object} DeliveryDrop
 * @property {string} recipientName
 * @property {string | null} phone
 * @property {string} line1
 * @property {string | null} line2
 * @property {string | null} landmark
 * @property {string} city
 * @property {string | null} postalCode
 * @property {number | null} latitude
 * @property {number | null} longitude
 */

/**
 * @typedef {object} DeliveryFailure
 * @property {DeliveryFailReason | (string & {})} reason
 * @property {string | null} note
 */

/**
 * @typedef {object} DeliveryTimelineEntry
 * @property {DeliveryStatus} status
 * @property {string} at
 */

/**
 * `DeliveryView` — the assigned partner (and admins) see the drop details.
 * @typedef {object} DeliveryView
 * @property {string} deliveryId
 * @property {string} orderId
 * @property {string} orderNumber
 * @property {OrderType} orderType
 * @property {DeliveryStatus} status
 * @property {boolean} readyForPickup The store marked the order ready (OrderReadyForPickup).
 * @property {string | null} readyBy
 * @property {DeliveryPickup | null} pickup Null for an order confirmed before Phase 9 (no store location snapshot).
 * @property {DeliveryDrop} drop
 * @property {string | null} note The customer's delivery instructions.
 * @property {number} itemCount
 * @property {number} total
 * @property {string} currency
 * @property {string | null} assignedAt
 * @property {string | null} pickedUpAt
 * @property {string | null} deliveredAt
 * @property {string | null} failedAt
 * @property {DeliveryFailure | null} failure
 * @property {DeliveryTimelineEntry[]} timeline Oldest first.
 */

/**
 * `PUT /api/delivery/partners/me/location`.
 * @typedef {object} LocationReport
 * @property {number} latitude
 * @property {number} longitude
 * @property {number} [accuracyMeters]
 * @property {number} [heading] Degrees clockwise from north.
 * @property {number} [speedKmh]
 */

/**
 * @typedef {object} FailDeliveryRequest
 * @property {DeliveryFailReason} reason
 * @property {string} [note] ≤ 300; required for OTHER.
 */

/**
 * One finished delivery of the history (newest first).
 * @typedef {object} DeliverySummary
 * @property {string} deliveryId
 * @property {string} orderNumber
 * @property {Extract<DeliveryStatus, 'DELIVERED' | 'FAILED'> | (string & {})} status
 * @property {string | { name: string } | null} store The contract names it "`store` name": a plain name, or `{ name }`.
 * @property {string | null} dropArea
 * @property {number | null} tripKm
 * @property {string | null} pickedUpAt
 * @property {string | null} finishedAt
 */

/**
 * India-time days; the week is the last 7 days.
 * @typedef {object} DeliveryHistorySummary
 * @property {number} deliveredToday
 * @property {number} deliveredThisWeek
 * @property {number} failedThisWeek
 */

/**
 * @typedef {object} HistoryParams
 * @property {number} [page]
 * @property {number} [size]
 * @property {Extract<DeliveryStatus, 'DELIVERED' | 'FAILED'>} [status] One outcome; absent = both.
 * @property {string} [from] India-time days the trip ended on, `YYYY-MM-DD`, both included; either may be absent.
 * @property {string} [to]
 */

/** Limits from the contract. */
export const DELIVERY_LIMITS = { failNote: 300, reassignReason: 300 }

export const DELIVERY_ERRORS = {
  /** 403 on every partner endpoint while the partner is not APPROVED. */
  partnerNotActive: 'PARTNER_NOT_ACTIVE',
  /** Going offline with an active delivery. */
  activeDelivery: 'ACTIVE_DELIVERY',
  /** A location report while offline. */
  partnerOffline: 'PARTNER_OFFLINE',
  offerNotFound: 'OFFER_NOT_FOUND',
  /** Not PENDING any more, expired, or another partner took the delivery (`currentStatus` = the offer's status). */
  offerNotAvailable: 'OFFER_NOT_AVAILABLE',
  deliveryNotFound: 'DELIVERY_NOT_FOUND',
  /** "Picked up" before the store marked the order ready. */
  orderNotReady: 'ORDER_NOT_READY',
  invalidTransition: 'INVALID_DELIVERY_TRANSITION',
}

export const deliveryKeys = {
  all: ['partner-deliveries'],
  status: () => [...deliveryKeys.all, 'status'],
  offers: () => [...deliveryKeys.all, 'offers'],
  active: () => [...deliveryKeys.all, 'active'],
  /** Prefix of the history list pages and the summary tiles. */
  history: () => [...deliveryKeys.all, 'history'],
  historyList: (params) => [...deliveryKeys.history(), 'list', params],
  historySummary: () => [...deliveryKeys.history(), 'summary'],
  /** Under `history`, so finishing a delivery refreshes the dashboard's charts too. */
  stats: (range) => [...deliveryKeys.history(), 'stats', range],
}

/** @param {string} id */
const deliveryPath = (id) => `/delivery/deliveries/${segment(id)}`
/** @param {string} id */
const offerPath = (id) => `/delivery/offers/${segment(id)}`

/** @returns {Promise<PartnerStatusView>} */
export async function fetchPartnerStatus() {
  return (await httpClient.get('/delivery/partners/me/status')).data
}

/**
 * Going offline with an active delivery → 409 ACTIVE_DELIVERY.
 * @param {boolean} online
 * @returns {Promise<PartnerStatusView>}
 */
export async function setAvailability(online) {
  return (await httpClient.put('/delivery/partners/me/availability', { online })).data
}

/**
 * 204. Offline → 409 PARTNER_OFFLINE.
 * @param {LocationReport} report
 * @returns {Promise<void>}
 */
export async function reportLocation(report) {
  await httpClient.put('/delivery/partners/me/location', report)
}

/**
 * The partner's PENDING offers.
 * @returns {Promise<OfferView[]>}
 */
export async function listOffers() {
  return (await httpClient.get('/delivery/offers')).data
}

/**
 * → the delivery (ASSIGNED). 404 OFFER_NOT_FOUND; 409 OFFER_NOT_AVAILABLE.
 * @param {string} offerId
 * @returns {Promise<DeliveryView>}
 */
export async function acceptOffer(offerId) {
  return (await httpClient.post(`${offerPath(offerId)}/accept`)).data
}

/**
 * 204, idempotent.
 * @param {string} offerId
 * @returns {Promise<void>}
 */
export async function rejectOffer(offerId) {
  await httpClient.post(`${offerPath(offerId)}/reject`)
}

/**
 * The partner's active delivery, or null (204).
 * @returns {Promise<DeliveryView | null>}
 */
export async function fetchActiveDelivery() {
  const response = await httpClient.get('/delivery/deliveries/active')
  return response.status === 204 || !response.data ? null : response.data
}

/**
 * → PICKED_UP. 409 ORDER_NOT_READY / INVALID_DELIVERY_TRANSITION.
 * @param {string} id
 * @returns {Promise<DeliveryView>}
 */
export async function pickUpDelivery(id) {
  return (await httpClient.post(`${deliveryPath(id)}/pickup`)).data
}

/**
 * → DELIVERED (from PICKED_UP).
 * @param {string} id
 * @returns {Promise<DeliveryView>}
 */
export async function completeDelivery(id) {
  return (await httpClient.post(`${deliveryPath(id)}/deliver`)).data
}

/**
 * → FAILED (from PICKED_UP).
 * @param {string} id
 * @param {FailDeliveryRequest} body
 * @returns {Promise<DeliveryView>}
 */
export async function failDelivery(id, body) {
  return (await httpClient.post(`${deliveryPath(id)}/fail`, body)).data
}

/**
 * Newest first; `status`, `from` and `to` filter it (the export takes the same filters).
 * @param {HistoryParams} params
 * @returns {Promise<PageResponse<DeliverySummary>>}
 */
export async function listDeliveryHistory(params) {
  return (await httpClient.get('/delivery/history', { params: cleanParams(params) })).data
}

/** @returns {Promise<DeliveryHistorySummary>} */
export async function fetchDeliveryHistorySummary() {
  return (await httpClient.get('/delivery/history/summary')).data
}

/**
 * India-time days, both included.
 * @typedef {object} StatsRange
 * @property {string} from
 * @property {string} to
 */

/** The server answers at most this many days per request. */
export const MAX_STATS_DAYS = 92

/**
 * `GET /api/delivery/stats` — the partner's own numbers over the range. Counts go by when things happened (assigned,
 * ended, cancelled, offer sent); `inProgress` and `offersPending` are right now. Rates are 0–1 or null when there is
 * nothing to compare; averages are minutes or null.
 * @typedef {object} PartnerStatsTotals
 * @property {number} assigned
 * @property {number} delivered
 * @property {number} failed
 * @property {number} cancelled
 * @property {number} inProgress
 * @property {number} offersPending
 * @property {number} offersReceived
 * @property {number} offersAccepted
 * @property {number} offersRejected
 * @property {number} offersExpired
 * @property {number | null} successRate
 * @property {number | null} acceptanceRate
 * @property {number} distanceKm
 * @property {number} earnings Estimated with `payRate`.
 * @property {number | null} avgPickupMinutes
 * @property {number | null} avgRideMinutes
 * @property {number | null} avgTotalMinutes
 * @property {number} onlineMinutes
 */

/**
 * @typedef {object} PartnerStatsDay
 * @property {string} date `YYYY-MM-DD`
 * @property {number} delivered
 * @property {number} failed
 * @property {number} cancelled
 * @property {number} earnings
 * @property {number} distanceKm
 * @property {number} onlineMinutes
 */

/**
 * @typedef {object} PartnerStatsView
 * @property {string} from
 * @property {string} to
 * @property {string} timezone
 * @property {PartnerStatsTotals} totals
 * @property {PartnerStatsDay[]} days Every day of the range, oldest first.
 * @property {{ basePerDelivery: number, perKm: number, currency: string }} payRate Per delivered order: base + perKm × trip km.
 */

/**
 * @param {StatsRange} range
 * @returns {Promise<PartnerStatsView>}
 */
export async function fetchPartnerStats(range) {
  return (await httpClient.get('/delivery/stats', { params: range })).data
}
