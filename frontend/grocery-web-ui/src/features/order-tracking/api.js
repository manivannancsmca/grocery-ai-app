import { httpClient } from '../../shared/api/httpClient'
import { segment } from '../../shared/api/types'
/** @typedef {import('../delivery/api').VehicleType} VehicleType */
/** @typedef {import('../deliveries/api').DeliveryStatus} DeliveryStatus */
/** @typedef {import('../deliveries/api').DeliveryTimelineEntry} DeliveryTimelineEntry */

/**
 * Live tracking of one order (delivery-service — Phase 9 contract §4). Role CUSTOMER (own orders) or any admin.
 * No delivery yet, or another customer's order → 404 TRACKING_NOT_FOUND.
 */

/**
 * First name only.
 * @typedef {object} TrackingPartner
 * @property {string} name
 * @property {VehicleType | (string & {}) | null} vehicleType
 * @property {string | null} [phone] The partner's phone (`+919840066004`), to call them; only while they hold the delivery (ASSIGNED / PICKED_UP), null afterwards and absent from older answers.
 * @property {string | null} [vehicleNumber] The number plate (`TN09AB4521`), to spot them; same rule as `phone`.
 */

/**
 * @typedef {object} TrackingPickup
 * @property {string} name
 * @property {number | null} latitude
 * @property {number | null} longitude
 */

/**
 * @typedef {object} TrackingDrop
 * @property {number | null} latitude
 * @property {number | null} longitude
 */

/**
 * Only while the delivery is ASSIGNED or PICKED_UP.
 * @typedef {object} TrackingLocation
 * @property {number} latitude
 * @property {number} longitude
 * @property {number | null} heading
 * @property {string} at
 */

/**
 * `TrackingView`
 * @typedef {object} TrackingView
 * @property {string} orderId
 * @property {DeliveryStatus} status The delivery's status (PENDING … DELIVERED / FAILED / CANCELLED).
 * @property {TrackingPartner | null} partner
 * @property {TrackingPickup | null} pickup
 * @property {TrackingDrop | null} drop
 * @property {TrackingLocation | null} partnerLocation
 * @property {number | null} etaMinutes Partner → (store →) customer, whole minutes.
 * @property {DeliveryTimelineEntry[]} timeline
 */

export const TRACKING_NOT_FOUND = 'TRACKING_NOT_FOUND'

export const trackingKeys = {
  all: ['order-tracking'],
  detail: (orderId) => [...trackingKeys.all, orderId],
}

/**
 * @param {string} orderId
 * @returns {Promise<TrackingView>}
 */
export async function fetchTracking(orderId) {
  return (await httpClient.get(`/delivery/orders/${segment(orderId)}/tracking`)).data
}
