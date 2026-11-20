import type { IconName } from '../../shared/icons'
import type { OrderPaymentStatus, OrderStatus, OrderType, OrderView } from './api'

/**
 * The customer's view of where an order is, derived only from its status and timeline (no extra data): five
 * steps from "Placed" to "Delivered", or the steps it reached followed by the way it ended (cancelled, rejected,
 * not delivered).
 */

export type ProgressState = 'done' | 'current' | 'upcoming' | 'failed'

export interface ProgressStep {
  key: string
  label: string
  icon: IconName
  state: ProgressState
  /** When the order reached this step (from the timeline), if known. */
  at: string | null
}

interface StepDef {
  key: string
  label: (type: OrderType) => string
  icon: (type: OrderType) => IconName
  statuses: readonly OrderStatus[]
}

const STEPS: readonly StepDef[] = [
  { key: 'placed', label: () => 'Placed', icon: () => 'receipt', statuses: ['CREATED', 'AWAITING_PAYMENT'] },
  { key: 'confirmed', label: () => 'Confirmed', icon: () => 'checkCircle', statuses: ['CONFIRMED'] },
  {
    key: 'store',
    label: (type) => (type === 'RESTAURANT' ? 'Preparing' : 'Packing'),
    icon: (type) => (type === 'RESTAURANT' ? 'cooking' : 'box'),
    statuses: ['ACCEPTED', 'PREPARING', 'READY_FOR_PICKUP'],
  },
  { key: 'way', label: () => 'On the way', icon: () => 'scooter', statuses: ['OUT_FOR_DELIVERY'] },
  { key: 'delivered', label: () => 'Delivered', icon: () => 'homeCheck', statuses: ['DELIVERED'] },
]

const FAILURE: Partial<Record<OrderStatus, { label: string; icon: IconName }>> = {
  CANCELLED: { label: 'Cancelled', icon: 'xCircle' },
  REJECTED: { label: 'Not accepted', icon: 'ban' },
  DELIVERY_FAILED: { label: 'Not delivered', icon: 'warning' },
}

function stepIndex(status: OrderStatus): number {
  return STEPS.findIndex((step) => step.statuses.includes(status))
}

export function orderProgress(order: Pick<OrderView, 'status' | 'type' | 'timeline' | 'updatedAt'>): ProgressStep[] {
  const reachedAt = (index: number): string | null =>
    order.timeline.find((entry) => STEPS[index]?.statuses.includes(entry.status))?.at ?? null
  const failure = FAILURE[order.status]

  if (failure) {
    // The furthest normal step the timeline shows, then how it ended.
    const furthest = order.timeline.reduce((max, entry) => Math.max(max, stepIndex(entry.status)), 0)
    const ended = [...order.timeline].reverse().find((entry) => entry.status === order.status)?.at ?? order.updatedAt
    return [
      ...STEPS.slice(0, furthest + 1).map((step, index) => ({
        key: step.key,
        label: step.label(order.type),
        icon: step.icon(order.type),
        state: 'done' as const,
        at: reachedAt(index),
      })),
      { key: 'ended', label: failure.label, icon: failure.icon, state: 'failed' as const, at: ended },
    ]
  }

  const current = Math.max(0, stepIndex(order.status))
  return STEPS.map((step, index) => ({
    key: step.key,
    label: step.label(order.type),
    icon: step.icon(order.type),
    state: index < current || order.status === 'DELIVERED' ? 'done' : index === current ? 'current' : 'upcoming',
    at: index <= current ? reachedAt(index) : null,
  }))
}

/** Icon of each order status (pills on the orders list and the order page). */
export const ORDER_STATUS_ICONS: Record<OrderStatus, IconName> = {
  CREATED: 'receipt',
  AWAITING_PAYMENT: 'clock',
  CONFIRMED: 'checkCircle',
  ACCEPTED: 'checkCircle',
  PREPARING: 'cooking',
  READY_FOR_PICKUP: 'bag',
  OUT_FOR_DELIVERY: 'scooter',
  DELIVERED: 'homeCheck',
  DELIVERY_FAILED: 'warning',
  REJECTED: 'ban',
  CANCELLED: 'xCircle',
}

export const PAYMENT_STATUS_ICONS: Record<OrderPaymentStatus, IconName> = {
  NOT_STARTED: 'card',
  PENDING: 'clock',
  PAID: 'check',
  EXPIRED: 'xCircle',
  NOT_REQUIRED: 'card',
  REFUND_PENDING: 'refresh',
  REFUNDED: 'return',
  PARTIALLY_REFUNDED: 'return',
}
