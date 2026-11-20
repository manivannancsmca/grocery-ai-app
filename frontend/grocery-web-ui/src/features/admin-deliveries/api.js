import { httpClient } from '../../shared/api/httpClient'
import { cleanParams, segment } from '../../shared/api/types'
/** @typedef {import('../../shared/api/types').PageResponse} PageResponse */
/** @typedef {import('../delivery/api').VehicleType} VehicleType */
/** @typedef {import('../deliveries/api').DeliveryStatus} DeliveryStatus */
/** @typedef {import('../deliveries/api').DeliveryView} DeliveryView */

/**
 * Admin delivery monitor (delivery-service — Phase 9 contract §5). Reads: any admin role. Reassign: SUPER_ADMIN,
 * OPS_ADMIN, DELIVERY_MANAGER.
 */

/**
 * `AdminDeliveryView` — DeliveryView + the partner, whether the search found nobody, and how many offers were sent.
 * @typedef {DeliveryView & { partnerId: string | null, partnerName: string | null, assignmentFailed: boolean, offersSent?: number }} AdminDeliveryView
 * - `assignmentFailed`: SEARCHING, but nobody accepted within the largest radius (a fresh search runs every minute).
 * - `offersSent`: The contract says "offers sent"; the field name is assumed.
 */

export const OFFER_STATUSES = ['PENDING', 'ACCEPTED', 'REJECTED', 'EXPIRED', 'WITHDRAWN']
/** @typedef {(typeof OFFER_STATUSES)[number]} OfferStatus */

/**
 * One offer of a delivery, with its outcome.
 * @typedef {object} DeliveryOfferRecord
 * @property {string} offerId
 * @property {string} partnerId
 * @property {string | null} partnerName
 * @property {number | null} distanceKm
 * @property {OfferStatus | (string & {})} status
 * @property {string} sentAt
 * @property {string | null} closedAt
 */

/** @typedef {AdminDeliveryView & { offers: DeliveryOfferRecord[] }} AdminDeliveryDetail */

/**
 * `GET /api/admin/delivery-partners/online` — one online partner.
 * @typedef {object} OnlinePartner
 * @property {string} partnerId
 * @property {string | null} name
 * @property {VehicleType | (string & {}) | null} vehicleType
 * @property {number | null} latitude
 * @property {number | null} longitude
 * @property {string | null} locationAt
 * @property {boolean} busy Has an active delivery (cannot be assigned).
 * @property {string | null} activeDeliveryId
 */

/**
 * @typedef {object} AdminDeliveryListParams
 * @property {DeliveryStatus} [status]
 * @property {boolean} [assignmentFailed] true → only deliveries for which nobody was found.
 * @property {string} [partnerId]
 * @property {string} [q] Order number.
 * @property {number} [page]
 * @property {number} [size]
 */

/**
 * @typedef {object} ReassignRequest
 * @property {string} [partnerId] Absent: drop the current partner and search again (excluding them).
 * @property {string} [reason] ≤ 300.
 */

export const ADMIN_DELIVERY_ERRORS = {
  notFound: 'DELIVERY_NOT_FOUND',
  /** The chosen partner is not APPROVED, online and free. */
  partnerUnavailable: 'PARTNER_UNAVAILABLE',
  /** Reassign is only possible while SEARCHING or ASSIGNED. */
  invalidTransition: 'INVALID_DELIVERY_TRANSITION',
}

/**
 * Reassign applies only to these statuses.
 * @type {readonly DeliveryStatus[]}
 */
export const REASSIGNABLE_STATUSES = ['SEARCHING', 'ASSIGNED']

export const adminDeliveryKeys = {
  all: ['admin', 'deliveries'],
  lists: () => [...adminDeliveryKeys.all, 'list'],
  list: (params) => [...adminDeliveryKeys.lists(), params],
  detail: (id) => [...adminDeliveryKeys.all, 'detail', id],
  onlinePartners: () => [...adminDeliveryKeys.all, 'online-partners'],
}

const base = '/admin/deliveries'

/**
 * Newest first.
 * @param {AdminDeliveryListParams} params
 * @returns {Promise<PageResponse<AdminDeliveryView>>}
 */
export async function listAdminDeliveries(params) {
  return (await httpClient.get(base, { params: cleanParams(params) })).data
}

/**
 * @param {string} id
 * @returns {Promise<AdminDeliveryDetail>}
 */
export async function fetchAdminDelivery(id) {
  return (await httpClient.get(`${base}/${segment(id)}`)).data
}

/**
 * 409 PARTNER_UNAVAILABLE / INVALID_DELIVERY_TRANSITION.
 * @param {string} id
 * @param {ReassignRequest} body
 * @returns {Promise<AdminDeliveryView>}
 */
export async function reassignDelivery(id, body) {
  return (await httpClient.post(`${base}/${segment(id)}/reassign`, body)).data
}

/** @returns {Promise<OnlinePartner[]>} */
export async function listOnlinePartners() {
  return (await httpClient.get('/admin/delivery-partners/online')).data
}
