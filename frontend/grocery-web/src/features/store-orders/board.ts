import type { StoreOrderItem, StoreOrderStatus, StoreOrderView } from './api'

/** Under this much time left, the acceptance countdown (and the New column's "next expires" chip) turns red. */
export const URGENT_MS = 60_000
/** A NEW order received this recently is marked "Just in". */
export const FRESH_MS = 60_000
/** Items a card lists before folding the rest under "+N more" (a card with one more than this shows them all). */
export const ITEMS_SHOWN = 3

const time = (value: string | null) => (value ? Date.parse(value) : Number.POSITIVE_INFINITY)

/** Most urgent first: NEW by deadline, accepted / preparing by promised time, ready by how long it has waited. */
export function columnOrder(status: StoreOrderStatus): (a: StoreOrderView, b: StoreOrderView) => number {
  switch (status) {
    case 'NEW':
      return (a, b) => time(a.acceptBy) - time(b.acceptBy)
    case 'READY_FOR_PICKUP':
      return (a, b) => time(a.readyAt) - time(b.readyAt)
    default:
      return (a, b) => time(a.readyBy ?? a.receivedAt) - time(b.readyBy ?? b.receivedAt)
  }
}

/** Whole minutes past the promised "ready by" time (0 while on time or without one). */
export function minutesLate(order: Pick<StoreOrderView, 'readyBy'>, now: number): number {
  if (!order.readyBy) return 0
  const late = Math.floor((now - Date.parse(order.readyBy)) / 60_000)
  return Number.isFinite(late) && late > 0 ? late : 0
}

/** Accepted or preparing orders at least a minute past their promised time. */
export function lateCount(orders: readonly StoreOrderView[], now: number): number {
  return orders.filter((order) => (order.status === 'ACCEPTED' || order.status === 'PREPARING') && minutesLate(order, now) >= 1).length
}

/** Milliseconds until the soonest NEW order is rejected automatically (never negative); null without NEW orders. */
export function nextExpiryMs(orders: readonly StoreOrderView[], now: number): number | null {
  const deadlines = orders.filter((order) => order.status === 'NEW').map((order) => Date.parse(order.acceptBy)).filter(Number.isFinite)
  return deadlines.length ? Math.max(0, Math.min(...deadlines) - now) : null
}

/**
 * The ready time still to come, in whole minutes ("in 18 min"); 0 once it is due. Rounded to the nearest minute: an
 * order accepted "ready in 15" reads "in 15 min" at once, whichever side of the second the board's clock ticked.
 */
export function minutesUntil(at: string | null, now: number): number {
  if (!at) return 0
  const left = Math.round((Date.parse(at) - now) / 60_000)
  return Number.isFinite(left) && left > 0 ? left : 0
}

/** How much of the acceptance window is left, 0–1 (1 when the window is unknown): the width of the draining bar. */
export function acceptWindowLeft(order: Pick<StoreOrderView, 'acceptBy' | 'receivedAt'>, now: number): number {
  const deadline = Date.parse(order.acceptBy)
  const span = deadline - Date.parse(order.receivedAt)
  if (!Number.isFinite(span) || span <= 0) return 1
  return Math.min(1, Math.max(0, (deadline - now) / span))
}

/** The items a card lists, and how many lines are folded away (none when only one would be). */
export function foldItems(items: readonly StoreOrderItem[], expanded: boolean): { shown: readonly StoreOrderItem[]; hidden: number } {
  if (expanded || items.length <= ITEMS_SHOWN + 1) return { shown: items, hidden: 0 }
  return { shown: items.slice(0, ITEMS_SHOWN), hidden: items.length - ITEMS_SHOWN }
}

/** Today's picked-up orders and what they sold (item totals: the delivery fee is not the store's). */
export function pickedUpToday(closed: readonly StoreOrderView[]): { count: number; sales: number; currency: string } {
  const done = closed.filter((order) => order.status === 'PICKED_UP')
  return { count: done.length, sales: done.reduce((sum, order) => sum + order.subtotal, 0), currency: done[0]?.currency ?? 'INR' }
}

/** Where an order is on the board: 1 New · 2 Accepted · 3 Preparing / Packing · 4 Ready (0 off the board). */
export function boardStep(status: StoreOrderStatus): number {
  return ['NEW', 'ACCEPTED', 'PREPARING', 'READY_FOR_PICKUP'].indexOf(status) + 1
}
