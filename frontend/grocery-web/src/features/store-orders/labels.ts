import type { IconName } from '../../shared/icons'
import { cancelledByLabel } from '../orders/labels'
import {
  STORE_KIND_CONFIG,
  type StoreKind,
  type StoreOrderCancellation,
  type StoreOrderRejection,
  type StoreOrderStatus,
  type StoreOrderView,
  type StoreRejectReason,
} from './api'

/** Status words on the board; PREPARING reads "Packing" for groceries. */
export function storeOrderStatusLabel(kind: StoreKind, status: string): string {
  switch (status) {
    case 'NEW':
      return 'New'
    case 'ACCEPTED':
      return 'Accepted'
    case 'PREPARING':
      return STORE_KIND_CONFIG[kind].preparingLabel
    case 'READY_FOR_PICKUP':
      return 'Ready for pickup'
    case 'PICKED_UP':
      return 'Picked up'
    case 'REJECTED':
      return 'Rejected'
    case 'CANCELLED':
      return 'Cancelled'
    default:
      return status
  }
}

/** The reject reasons a store user picks from. */
export const STORE_REJECT_REASON_LABELS: Record<StoreRejectReason, string> = {
  OUT_OF_STOCK: 'Items out of stock',
  STORE_CLOSED: 'Store closed',
  TOO_BUSY: 'Too busy right now',
  OTHER: 'Other (add a note)',
}

/** The four board columns, in order. */
export const BOARD_COLUMNS: readonly StoreOrderStatus[] = ['NEW', 'ACCEPTED', 'PREPARING', 'READY_FOR_PICKUP']

/** Column titles: New · Accepted · Preparing (Packing) · Ready. */
export function boardColumnTitle(kind: StoreKind, status: StoreOrderStatus): string {
  return status === 'READY_FOR_PICKUP' ? 'Ready' : storeOrderStatusLabel(kind, status)
}

export function boardColumnEmptyText(kind: StoreKind, status: StoreOrderStatus): string {
  switch (status) {
    case 'NEW':
      return 'No new orders right now.'
    case 'ACCEPTED':
      return 'Nothing waiting to start.'
    case 'PREPARING':
      return kind === 'VENDOR' ? 'Nothing being packed.' : 'Nothing being prepared.'
    default:
      return 'Nothing waiting for pickup.'
  }
}

/** How a board column (and its orders' step) looks: its icon and colours (class names spelled out so Tailwind keeps them). */
export interface BoardColumnStyle {
  icon: IconName
  /** The status dot in the column header and the phone switcher, and the filled steps of a card. */
  dot: string
  /** The count pill when the column has orders. */
  count: string
  /** The icon tile in the column header and the empty state. */
  tile: string
}

export function boardColumnStyle(kind: StoreKind, status: StoreOrderStatus): BoardColumnStyle {
  switch (status) {
    case 'NEW':
      return { icon: 'bell', dot: 'bg-amber-500', count: 'bg-amber-500 text-white', tile: 'bg-amber-50 ring-amber-200' }
    case 'ACCEPTED':
      return { icon: 'checkCircle', dot: 'bg-sky-500', count: 'bg-sky-600 text-white', tile: 'bg-sky-50 ring-sky-200' }
    case 'PREPARING':
      return { icon: kind === 'VENDOR' ? 'box' : 'cooking', dot: 'bg-violet-500', count: 'bg-violet-600 text-white', tile: 'bg-violet-50 ring-violet-200' }
    default:
      return { icon: 'bag', dot: 'bg-emerald-500', count: 'bg-emerald-600 text-white', tile: 'bg-emerald-50 ring-emerald-200' }
  }
}

/** The tone of a "Closed today" row's status chip. */
export type ClosedTone = 'done' | 'rejected' | 'cancelled'

/** A "Closed today" row: its status chip and the reason, which reads on from the chip ("Rejected" "items out of stock"). */
export interface ClosedOrderSummary {
  tone: ClosedTone
  chip: string
  reason: string | null
  /** The store's own words to the customer (a rejection note). */
  note: string | null
}

const REJECTION_REASONS: Record<string, string> = {
  OUT_OF_STOCK: 'items out of stock',
  STORE_CLOSED: 'store closed',
  TOO_BUSY: 'too busy',
}

function rejectionSummary(rejection: StoreOrderRejection): ClosedOrderSummary {
  const note = rejection.note?.trim() || null
  if (rejection.auto || rejection.reason === 'NO_RESPONSE') return { tone: 'rejected', chip: 'Auto-rejected', reason: 'not accepted in time', note }
  const reason = rejection.reason === 'OTHER' ? null : (REJECTION_REASONS[rejection.reason] ?? rejection.reason.toLowerCase().replaceAll('_', ' '))
  return { tone: 'rejected', chip: 'Rejected', reason, note }
}

function cancellationSummary(cancellation: StoreOrderCancellation | null): ClosedOrderSummary {
  const by = cancellation?.by
  const reason = !by ? null : by === 'CUSTOMER' ? 'by the customer' : by === 'ADMIN' ? 'by support' : `by ${cancelledByLabel(by)}`
  return { tone: 'cancelled', chip: 'Cancelled', reason, note: null }
}

/** What happened to an order on the "Closed today" list: picked up, (auto-)rejected and why, or cancelled and by whom. */
export function closedOrderSummary(kind: StoreKind, order: Pick<StoreOrderView, 'status' | 'rejection' | 'cancellation'>): ClosedOrderSummary {
  if (order.status === 'PICKED_UP') return { tone: 'done', chip: 'Picked up', reason: 'by the delivery partner', note: null }
  if (order.rejection) return rejectionSummary(order.rejection)
  if (order.status === 'CANCELLED' || order.cancellation) return cancellationSummary(order.cancellation)
  return { tone: 'cancelled', chip: storeOrderStatusLabel(kind, order.status), reason: null, note: null }
}
