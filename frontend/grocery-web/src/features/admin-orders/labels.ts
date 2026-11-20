import type { BadgeTone } from '../../shared/components/ui'
import { humanizeToken } from '../../shared/utils/format'
import type { OrderEventDirection, OrderEventOutcome, StuckOrderReason } from './api'

/** The admin orders page with this order's drawer open and the list narrowed to it (`?q=` + `?order=`). */
export function adminOrderLink(order: { orderId: string; orderNumber?: string | null }): string {
  const params = new URLSearchParams()
  if (order.orderNumber) params.set('q', order.orderNumber)
  params.set('order', order.orderId)
  return `/admin/orders?${params.toString()}`
}

/** Phase 10 §4: what is overdue, per watchdog reason. */
export const STUCK_REASON_LABELS: Record<StuckOrderReason, string> = {
  STOCK_CHECK_OVERDUE: 'Stock check overdue',
  PAYMENT_EXPIRY_OVERDUE: 'Payment expiry overdue',
  STORE_RESPONSE_OVERDUE: 'Store response overdue',
  PREPARATION_OVERDUE: 'Preparation overdue',
  PICKUP_OVERDUE: 'Pickup overdue',
  DELIVERY_OVERDUE: 'Delivery overdue',
}

export function stuckReasonLabel(reason: string): string {
  return reason in STUCK_REASON_LABELS ? STUCK_REASON_LABELS[reason as StuckOrderReason] : humanizeToken(reason)
}

/** `45` → `45 min`, `90` → `1 h 30 min`, `120` → `2 h`. */
export function formatOverdue(minutes: number): string {
  const whole = Math.max(0, Math.round(minutes))
  if (whole < 60) return `${whole} min`
  const hours = Math.floor(whole / 60)
  const rest = whole % 60
  return rest === 0 ? `${hours} h` : `${hours} h ${rest} min`
}

export const EVENT_DIRECTION_LABELS: Record<OrderEventDirection, string> = { PUBLISHED: 'Published', CONSUMED: 'Consumed' }
export const EVENT_DIRECTION_TONES: Record<OrderEventDirection, BadgeTone> = { PUBLISHED: 'blue', CONSUMED: 'violet' }

export const EVENT_OUTCOME_LABELS: Record<OrderEventOutcome, string> = { APPLIED: 'Applied', IGNORED: 'Ignored', DUPLICATE: 'Duplicate' }
export const EVENT_OUTCOME_TONES: Record<OrderEventOutcome, BadgeTone> = { APPLIED: 'green', IGNORED: 'amber', DUPLICATE: 'gray' }

export function labelOf<T extends string>(labels: Record<T, string>, value: string): string {
  return value in labels ? labels[value as T] : humanizeToken(value)
}

export function toneOf<T extends string>(tones: Record<T, BadgeTone>, value: string): BadgeTone {
  return value in tones ? tones[value as T] : 'gray'
}
