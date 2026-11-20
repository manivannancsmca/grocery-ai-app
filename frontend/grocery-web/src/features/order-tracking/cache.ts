import type { QueryClient } from '@tanstack/react-query'
import { distanceKm, type GeoPoint, isGeoPoint } from '../../shared/components/map/types'
import { trackingKeys, type TrackingLocation, type TrackingView } from './api'

/** The `DELIVERY_LOCATION` signal's data (Phase 9 contract §7). */
export interface DeliveryLocationData {
  orderId?: string
  latitude?: number
  longitude?: number
  heading?: number | null
  at?: string
}

/** Road distance ≈ straight line × 1.3 at 20 km/h (contract §2), rounded up to whole minutes. */
export const ETA_ROAD_FACTOR = 1.3
export const ETA_SPEED_KMH = 20

export function etaMinutesFor(km: number): number {
  return Math.ceil(((km * ETA_ROAD_FACTOR) / ETA_SPEED_KMH) * 60)
}

/**
 * The ETA from a new partner position, the way delivery-service computes it: partner → store → customer
 * before pickup, partner → customer after. Without the points it needs, the previous ETA stays.
 */
export function estimateEta(tracking: TrackingView, position: GeoPoint): number | null {
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
 */
export function applyDeliveryLocation(queryClient: QueryClient, data: DeliveryLocationData): boolean {
  const { orderId, latitude, longitude } = data
  if (!orderId || typeof latitude !== 'number' || typeof longitude !== 'number') return false
  const tracking = queryClient.getQueryData<TrackingView>(trackingKeys.detail(orderId))
  if (!tracking || (tracking.status !== 'ASSIGNED' && tracking.status !== 'PICKED_UP')) return false

  const at = data.at ?? new Date().toISOString()
  const previous = tracking.partnerLocation
  if (previous && Date.parse(previous.at) > Date.parse(at)) return false

  const partnerLocation: TrackingLocation = { latitude, longitude, heading: data.heading ?? null, at }
  queryClient.setQueryData<TrackingView>(trackingKeys.detail(orderId), {
    ...tracking,
    partnerLocation,
    etaMinutes: estimateEta(tracking, partnerLocation),
  })
  return true
}
