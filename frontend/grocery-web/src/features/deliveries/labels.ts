import type { BadgeTone } from '../../shared/components/ui'
import { formatDistanceKm } from '../../shared/utils/format'
import type { DeliveryDrop, DeliveryFailReason, DeliveryStatus, DeliverySummary } from './api'

export const DELIVERY_STATUS_LABELS: Record<DeliveryStatus, string> = {
  PENDING: 'Waiting for the store',
  SEARCHING: 'Finding a partner',
  ASSIGNED: 'Partner assigned',
  PICKED_UP: 'Picked up',
  DELIVERED: 'Delivered',
  FAILED: 'Failed',
  CANCELLED: 'Cancelled',
}

export const DELIVERY_STATUS_TONES: Record<DeliveryStatus, BadgeTone> = {
  PENDING: 'gray',
  SEARCHING: 'amber',
  ASSIGNED: 'blue',
  PICKED_UP: 'violet',
  DELIVERED: 'green',
  FAILED: 'red',
  CANCELLED: 'gray',
}

export function deliveryStatusLabel(status: string): string {
  return status in DELIVERY_STATUS_LABELS ? DELIVERY_STATUS_LABELS[status as DeliveryStatus] : status
}

export function deliveryStatusTone(status: string): BadgeTone {
  return status in DELIVERY_STATUS_TONES ? DELIVERY_STATUS_TONES[status as DeliveryStatus] : 'gray'
}

/** The "Couldn't deliver" reasons a partner picks from. */
export const FAIL_REASON_LABELS: Record<DeliveryFailReason, string> = {
  CUSTOMER_UNREACHABLE: 'Could not reach the customer',
  ADDRESS_NOT_FOUND: 'Could not find the address',
  CUSTOMER_REFUSED: 'The customer refused the order',
  OTHER: 'Other (add a note)',
}

/** A failure reason in plain words (falls back to the raw code). */
export function failReasonText(reason: string): string {
  if (reason === 'OTHER') return 'Other'
  return reason in FAIL_REASON_LABELS ? FAIL_REASON_LABELS[reason as DeliveryFailReason] : reason
}

/**
 * `Indiranagar, Bengaluru 560038` — the drop's street lines, landmark, city and PIN on separate parts. The landmark is
 * shown as the customer wrote it ("Near the temple", "Opposite the bus stop").
 */
export function dropAddressLines(drop: DeliveryDrop): string[] {
  return [
    [drop.line1, drop.line2].filter(Boolean).join(', '),
    drop.landmark ?? '',
    [drop.city, drop.postalCode].filter(Boolean).join(' '),
  ].filter((line) => line.length > 0)
}

/** `+919876543210` → `tel:+919876543210` (spaces and dashes dropped). */
export function telHref(phone: string): string {
  return `tel:${phone.replace(/[^\d+]/g, '')}`
}

/** The store name of a history row (the contract sends the name, older drafts `{ name }`). */
export function historyStoreName(entry: Pick<DeliverySummary, 'store'>): string {
  if (!entry.store) return '—'
  return typeof entry.store === 'string' ? entry.store : entry.store.name
}

/** `1.2 km` or `—`. */
export function kmText(km: number | null | undefined): string {
  return formatDistanceKm(km) ?? '—'
}

/** `3 items` / `1 item`. */
export function itemsText(count: number): string {
  return `${count} item${count === 1 ? '' : 's'}`
}

/** The pay rate of `GET /delivery/stats`: a fixed amount per delivered order plus an amount per km of trip. */
export interface PayRate {
  basePerDelivery: number
  perKm: number
}

/**
 * What an offer would earn if delivered, by the same estimate as the partner's performance: the base per delivered
 * order plus the per-km rate times the trip (store → customer, straight line). Null without a pay rate or a trip.
 */
export function offerEarnings(tripKm: number | null | undefined, payRate: PayRate | null | undefined): number | null {
  if (!payRate || tripKm === null || tripKm === undefined || !Number.isFinite(tripKm)) return null
  return Math.round((payRate.basePerDelivery + payRate.perKm * tripKm) * 100) / 100
}
