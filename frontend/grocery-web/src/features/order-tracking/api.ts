import { httpClient } from '../../shared/api/httpClient'
import { segment } from '../../shared/api/types'
import type { VehicleType } from '../delivery/api'
import type { DeliveryStatus, DeliveryTimelineEntry } from '../deliveries/api'

/**
 * Live tracking of one order (delivery-service — Phase 9 contract §4). Role CUSTOMER (own orders) or any admin.
 * No delivery yet, or another customer's order → 404 TRACKING_NOT_FOUND.
 */

/** First name only. */
export interface TrackingPartner {
  name: string
  vehicleType: VehicleType | (string & {}) | null
  /**
   * The partner's phone (`+919840066004`), to call them; only while they hold the delivery (ASSIGNED / PICKED_UP),
   * null afterwards and absent from older answers.
   */
  phone?: string | null
  /** The number plate (`TN09AB4521`), to spot them; same rule as `phone`. */
  vehicleNumber?: string | null
}

export interface TrackingPickup {
  name: string
  latitude: number | null
  longitude: number | null
}

export interface TrackingDrop {
  latitude: number | null
  longitude: number | null
}

/** Only while the delivery is ASSIGNED or PICKED_UP. */
export interface TrackingLocation {
  latitude: number
  longitude: number
  heading: number | null
  at: string
}

/** `TrackingView` */
export interface TrackingView {
  orderId: string
  /** The delivery's status (PENDING … DELIVERED / FAILED / CANCELLED). */
  status: DeliveryStatus
  partner: TrackingPartner | null
  pickup: TrackingPickup | null
  drop: TrackingDrop | null
  partnerLocation: TrackingLocation | null
  /** Partner → (store →) customer, whole minutes. */
  etaMinutes: number | null
  timeline: DeliveryTimelineEntry[]
}

export const TRACKING_NOT_FOUND = 'TRACKING_NOT_FOUND'

export const trackingKeys = {
  all: ['order-tracking'] as const,
  detail: (orderId: string) => [...trackingKeys.all, orderId] as const,
}

export async function fetchTracking(orderId: string): Promise<TrackingView> {
  return (await httpClient.get<TrackingView>(`/delivery/orders/${segment(orderId)}/tracking`)).data
}
