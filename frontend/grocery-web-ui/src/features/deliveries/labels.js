import { formatDistanceKm } from '../../shared/utils/format'

/** @typedef {import('../../shared/components/ui').BadgeTone} BadgeTone */
/** @typedef {import('./api').DeliveryDrop} DeliveryDrop */
/** @typedef {import('./api').DeliveryFailReason} DeliveryFailReason */
/** @typedef {import('./api').DeliveryStatus} DeliveryStatus */
/** @typedef {import('./api').DeliverySummary} DeliverySummary */

/** @type {Record<DeliveryStatus, string>} */
export const DELIVERY_STATUS_LABELS = {
  PENDING: 'Waiting for the store',
  SEARCHING: 'Finding a partner',
  ASSIGNED: 'Partner assigned',
  PICKED_UP: 'Picked up',
  DELIVERED: 'Delivered',
  FAILED: 'Failed',
  CANCELLED: 'Cancelled',
}

/** @type {Record<DeliveryStatus, BadgeTone>} */
export const DELIVERY_STATUS_TONES = {
  PENDING: 'gray',
  SEARCHING: 'amber',
  ASSIGNED: 'blue',
  PICKED_UP: 'violet',
  DELIVERED: 'green',
  FAILED: 'red',
  CANCELLED: 'gray',
}

/**
 * @param {string} status
 * @returns {string}
 */
export function deliveryStatusLabel(status) {
  return status in DELIVERY_STATUS_LABELS ? DELIVERY_STATUS_LABELS[status] : status
}

/**
 * @param {string} status
 * @returns {BadgeTone}
 */
export function deliveryStatusTone(status) {
  return status in DELIVERY_STATUS_TONES ? DELIVERY_STATUS_TONES[status] : 'gray'
}

/**
 * The "Couldn't deliver" reasons a partner picks from.
 * @type {Record<DeliveryFailReason, string>}
 */
export const FAIL_REASON_LABELS = {
  CUSTOMER_UNREACHABLE: 'Could not reach the customer',
  ADDRESS_NOT_FOUND: 'Could not find the address',
  CUSTOMER_REFUSED: 'The customer refused the order',
  OTHER: 'Other (add a note)',
}

/**
 * A failure reason in plain words (falls back to the raw code).
 * @param {string} reason
 * @returns {string}
 */
export function failReasonText(reason) {
  if (reason === 'OTHER') return 'Other'
  return reason in FAIL_REASON_LABELS ? FAIL_REASON_LABELS[reason] : reason
}

/**
 * `Indiranagar, Bengaluru 560038` — the drop's street lines, landmark, city and PIN on separate parts. The landmark is
 * shown as the customer wrote it ("Near the temple", "Opposite the bus stop").
 * @param {DeliveryDrop} drop
 * @returns {string[]}
 */
export function dropAddressLines(drop) {
  return [
    [drop.line1, drop.line2].filter(Boolean).join(', '),
    drop.landmark ?? '',
    [drop.city, drop.postalCode].filter(Boolean).join(' '),
  ].filter((line) => line.length > 0)
}

/**
 * `+919876543210` → `tel:+919876543210` (spaces and dashes dropped).
 * @param {string} phone
 * @returns {string}
 */
export function telHref(phone) {
  return `tel:${phone.replace(/[^\d+]/g, '')}`
}

/**
 * The store name of a history row (the contract sends the name, older drafts `{ name }`).
 * @param {Pick<DeliverySummary, 'store'>} entry
 * @returns {string}
 */
export function historyStoreName(entry) {
  if (!entry.store) return '—'
  return typeof entry.store === 'string' ? entry.store : entry.store.name
}

/**
 * `1.2 km` or `—`.
 * @param {number | null | undefined} km
 * @returns {string}
 */
export function kmText(km) {
  return formatDistanceKm(km) ?? '—'
}

/**
 * `3 items` / `1 item`.
 * @param {number} count
 * @returns {string}
 */
export function itemsText(count) {
  return `${count} item${count === 1 ? '' : 's'}`
}

/**
 * The pay rate of `GET /delivery/stats`: a fixed amount per delivered order plus an amount per km of trip.
 * @typedef {object} PayRate
 * @property {number} basePerDelivery
 * @property {number} perKm
 */

/**
 * What an offer would earn if delivered, by the same estimate as the partner's performance: the base per delivered
 * order plus the per-km rate times the trip (store → customer, straight line). Null without a pay rate or a trip.
 * @param {number | null | undefined} tripKm
 * @param {PayRate | null | undefined} payRate
 * @returns {number | null}
 */
export function offerEarnings(tripKm, payRate) {
  if (!payRate || tripKm === null || tripKm === undefined || !Number.isFinite(tripKm)) return null
  return Math.round((payRate.basePerDelivery + payRate.perKm * tripKm) * 100) / 100
}
