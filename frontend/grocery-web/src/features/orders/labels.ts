import type { BadgeTone } from '../../shared/components/ui'
import { formatPrice } from '../../shared/utils/format'
import type { CancellationReason, CancelledBy, OrderCancellation, OrderPaymentStatus, OrderStatus, OrderType, OrderView } from './api'

/** Badge labels. CREATED reads "Placed" (restaurant orders pass it at once; the order page says "Checking stock…"). */
export const ORDER_STATUS_LABELS: Record<OrderStatus, string> = {
  CREATED: 'Placed',
  AWAITING_PAYMENT: 'Awaiting payment',
  CONFIRMED: 'Confirmed',
  ACCEPTED: 'Accepted',
  PREPARING: 'Preparing',
  READY_FOR_PICKUP: 'Ready for pickup',
  OUT_FOR_DELIVERY: 'On the way',
  DELIVERED: 'Delivered',
  DELIVERY_FAILED: 'Delivery failed',
  REJECTED: 'Rejected',
  CANCELLED: 'Cancelled',
}

export const ORDER_STATUS_TONES: Record<OrderStatus, BadgeTone> = {
  CREATED: 'blue',
  AWAITING_PAYMENT: 'amber',
  CONFIRMED: 'green',
  ACCEPTED: 'green',
  PREPARING: 'blue',
  READY_FOR_PICKUP: 'violet',
  OUT_FOR_DELIVERY: 'blue',
  DELIVERED: 'green',
  DELIVERY_FAILED: 'red',
  REJECTED: 'red',
  CANCELLED: 'gray',
}

/** Who cancelled, inside a sentence ("By the store · 10:32"). */
export const CANCELLED_BY_LABELS: Record<CancelledBy, string> = {
  CUSTOMER: 'the customer',
  ADMIN: 'an admin',
  SYSTEM: 'the system',
  STORE: 'the store',
}

export function cancelledByLabel(by: string): string {
  return by in CANCELLED_BY_LABELS ? CANCELLED_BY_LABELS[by as CancelledBy] : by.toLowerCase()
}

/** The store's reject reasons inside a sentence (Phase 8 contract §6; OTHER has no words of its own). */
export const STORE_REJECTION_REASON_TEXT: Record<string, string> = {
  OUT_OF_STOCK: 'items out of stock',
  STORE_CLOSED: 'store closed',
  TOO_BUSY: 'too busy right now',
  OTHER: '',
  NO_RESPONSE: 'not accepted in time',
}

/**
 * The note of a STORE_REJECTED cancellation, which order-service writes as `"<reason>: <note>"`, in plain
 * words: `TOO_BUSY: Kitchen full` → `Too busy right now: Kitchen full`, `OTHER: Gas leak` → `Gas leak`.
 * A note in any other shape is shown as it is.
 */
export function storeRejectionDetails(note: string | null | undefined): string | null {
  const text = note?.trim()
  if (!text) return null
  const match = /^([A-Z_]+)(?::\s*([\s\S]*))?$/.exec(text)
  const code = match?.[1]
  if (!match || code === undefined || !(code in STORE_REJECTION_REASON_TEXT)) return text
  const parts = [STORE_REJECTION_REASON_TEXT[code], match[2]?.trim()].filter((part): part is string => Boolean(part))
  if (parts.length === 0) return null
  const sentence = parts.join(': ')
  return sentence.charAt(0).toUpperCase() + sentence.slice(1)
}

export const ORDER_TYPE_LABELS: Record<OrderType, string> = { GROCERY: 'Grocery', RESTAURANT: 'Restaurant' }

export const ORDER_PAYMENT_STATUS_LABELS: Record<OrderPaymentStatus, string> = {
  NOT_STARTED: 'Payment not started',
  PENDING: 'Payment pending',
  PAID: 'Paid',
  EXPIRED: 'Payment expired',
  NOT_REQUIRED: 'Not charged',
  REFUND_PENDING: 'Refund pending',
  REFUNDED: 'Refunded',
  PARTIALLY_REFUNDED: 'Partly refunded',
}

export const ORDER_PAYMENT_STATUS_TONES: Record<OrderPaymentStatus, BadgeTone> = {
  NOT_STARTED: 'gray',
  PENDING: 'amber',
  PAID: 'green',
  EXPIRED: 'red',
  NOT_REQUIRED: 'gray',
  REFUND_PENDING: 'violet',
  REFUNDED: 'violet',
  PARTIALLY_REFUNDED: 'violet',
}

/** Statuses after which nothing happens to the order (refunds still change the payment). */
export function isTerminalStatus(status: OrderStatus): boolean {
  return status === 'REJECTED' || status === 'CANCELLED' || status === 'DELIVERED' || status === 'DELIVERY_FAILED'
}

/** Phase 9: a partner has the order (or had it): nobody can cancel it any more, not even support. */
export function isPastPickup(status: OrderStatus): boolean {
  return status === 'OUT_FOR_DELIVERY' || status === 'DELIVERED' || status === 'DELIVERY_FAILED'
}

/** Phase 9: the order page shows live tracking (`trackable` from the server, or simply "on the way"). */
export function isTrackable(order: Pick<OrderView, 'status' | 'trackable'>): boolean {
  return order.trackable === true || order.status === 'OUT_FOR_DELIVERY'
}

export function isOpenStatus(status: OrderStatus): boolean {
  return status === 'CREATED' || status === 'AWAITING_PAYMENT'
}

/** Phase 8: the store has accepted the order and is working on it (the customer can no longer cancel). */
export function isWithStore(status: OrderStatus): boolean {
  return status === 'ACCEPTED' || status === 'PREPARING' || status === 'READY_FOR_PICKUP'
}

const CUSTOMER_CANCELLATION_TEXT: Record<CancellationReason, string> = {
  CUSTOMER_CANCELLED: 'You cancelled this order.',
  ADMIN_CANCELLED: 'Our support team cancelled this order.',
  PAYMENT_TIMEOUT: 'The order was cancelled because it was not paid within 15 minutes.',
  RESERVATION_EXPIRED: 'The order was cancelled because the store could not hold your items any longer.',
  STORE_REJECTED: 'The store could not accept your order, so it was cancelled.',
  STORE_NO_RESPONSE: 'The store did not respond in time, so your order was cancelled.',
}

const ADMIN_CANCELLATION_TEXT: Record<CancellationReason, string> = {
  CUSTOMER_CANCELLED: 'Cancelled by the customer',
  ADMIN_CANCELLED: 'Cancelled by an admin',
  PAYMENT_TIMEOUT: 'Not paid within the payment window',
  RESERVATION_EXPIRED: 'Stock reservation expired',
  STORE_REJECTED: 'Rejected by the store',
  STORE_NO_RESPONSE: 'The store did not respond in time',
}

/** The cancellation reason in plain words, for the customer. */
export function cancellationText(cancellation: OrderCancellation): string {
  return CUSTOMER_CANCELLATION_TEXT[cancellation.reason] ?? 'This order was cancelled.'
}

/** Short reason for admin screens. */
export function adminCancellationText(cancellation: OrderCancellation): string {
  return ADMIN_CANCELLATION_TEXT[cancellation.reason] ?? cancellation.reason
}

/** What a timeline entry's `reason` means (falls back to the raw code). */
export function timelineReasonText(reason: string): string {
  switch (reason) {
    case 'OUT_OF_STOCK':
      return 'Some items were out of stock'
    case 'CUSTOMER_CANCELLED':
    case 'ADMIN_CANCELLED':
    case 'PAYMENT_TIMEOUT':
    case 'RESERVATION_EXPIRED':
    case 'STORE_REJECTED':
    case 'STORE_NO_RESPONSE':
      return ADMIN_CANCELLATION_TEXT[reason]
    // Phase 9: why a DELIVERY_FAILED order could not be delivered.
    case 'CUSTOMER_UNREACHABLE':
      return 'The customer could not be reached'
    case 'ADDRESS_NOT_FOUND':
      return 'The address could not be found'
    case 'CUSTOMER_REFUSED':
      return 'The customer refused the order'
    default:
      return reason
  }
}

/** One sentence about the refund of a cancelled or partly refunded order, or null when there is none. */
export function refundText(order: Pick<OrderView, 'payment' | 'total'>): string | null {
  const { status, refundedAmount } = order.payment
  if (status === 'REFUND_PENDING') {
    return `Your refund of ${formatPrice(order.total - refundedAmount)} is on its way. It usually reaches your account in 5–7 working days.`
  }
  if (status === 'REFUNDED') return `${formatPrice(refundedAmount)} has been refunded to your original payment method.`
  if (status === 'PARTIALLY_REFUNDED') return `${formatPrice(refundedAmount)} of ${formatPrice(order.total)} has been refunded to your original payment method.`
  return null
}

/** `Basmati Rice · 3 items`. */
export function orderItemsSummary(firstItemName: string, itemCount: number): string {
  return `${firstItemName} · ${itemCount} item${itemCount === 1 ? '' : 's'}`
}
