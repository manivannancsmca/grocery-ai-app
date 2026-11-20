import { distanceKm, isGeoPoint } from '../../shared/components/map/types'
import { trackingKeys } from './api'

/** @typedef {import('@tanstack/react-query').QueryClient} QueryClient */
/** @typedef {import('../../shared/components/map/types').GeoPoint} GeoPoint */
/** @typedef {import('./api').TrackingView} TrackingView */

/**
 * The `DELIVERY_LOCATION` signal's data (Phase 9 contract §7).
 * @typedef {object} DeliveryLocationData
 * @property {string} [orderId]
 * @property {number} [latitude]
 * @property {number} [longitude]
 * @property {number | null} [heading]
 * @property {string} [at]
 */

/** Road distance ≈ straight line × 1.3 at 20 km/h (contract §2), rounded up to whole minutes. */
export const ETA_ROAD_FACTOR = 1.3
export const ETA_SPEED_KMH = 20

/**
 * @param {number} km
 * @returns {number}
 */
export function etaMinutesFor(km) {
  return Math.ceil(((km * ETA_ROAD_FACTOR) / ETA_SPEED_KMH) * 60)
}

/**
 * The ETA from a new partner position, the way delivery-service computes it: partner → store → customer
 * before pickup, partner → customer after. Without the points it needs, the previous ETA stays.
 * @param {TrackingView} tracking
 * @param {GeoPoint} position
 * @returns {number | null}
 */
export function estimateEta(tracking, position) {
  const drop = tracking.drop && isGeoPoint(tracking.drop) ? tracking.drop : null
  if (!drop) return tracking.etaMinutes
  if (tracking.status === 'ASSIGNED') {
    const pickup = tracking.pickup && isGeoPoint(tracking.pickup) ? tracking.pickup : null
    return etaMinutesFor(pickup ? distanceKm(position, pickup) + distanceKm(pickup, drop) : distanceKm(position, drop))
  }
  if (tracking.status === 'PICKED_UP') return etaMinutesFor(distanceKm(position, drop))
  return tracking.etaMinutes
}

/**
 * A live `DELIVERY_LOCATION` moves the partner in the cached tracking view directly — no refetch per ping.
 * Older positions than the cached one (out-of-order delivery) and orders that are not being tracked are ignored.
 * Returns whether the cache changed.
 * @param {QueryClient} queryClient
 * @param {DeliveryLocationData} data
 * @returns {boolean}
 */
export function applyDeliveryLocation(queryClient, data) {
  const { orderId, latitude, longitude } = data
  if (!orderId || typeof latitude !== 'number' || typeof longitude !== 'number') return false
  const tracking = queryClient.getQueryData(trackingKeys.detail(orderId))
  if (!tracking || (tracking.status !== 'ASSIGNED' && tracking.status !== 'PICKED_UP')) return false

  const at = data.at ?? new Date().toISOString()
  const previous = tracking.partnerLocation
  if (previous && Date.parse(previous.at) > Date.parse(at)) return false

  const partnerLocation = { latitude, longitude, heading: data.heading ?? null, at }
  queryClient.setQueryData(trackingKeys.detail(orderId), {
    ...tracking,
    partnerLocation,
    etaMinutes: estimateEta(tracking, partnerLocation),
  })
  return true
}
