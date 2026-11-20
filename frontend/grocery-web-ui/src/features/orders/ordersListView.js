import { formatTime } from '../../shared/utils/format'
import { formatLongDay, indiaDay, shiftDay } from '../../shared/utils/indiaDate'
import { isTerminalStatus, ORDER_STATUS_LABELS } from './labels'
import { orderProgress } from './progress'
import { orderStatusLook } from './statusSummary'

/** @typedef {import('../../shared/icons').IconName} IconName */
/** @typedef {import('./api').OrderSummary} OrderSummary */
/** @typedef {import('./progress').ProgressStep} ProgressStep */
/** @typedef {import('./statusSummary').StatusTone} StatusTone */

/**
 * The customer's orders list (`/orders`), from the list's own data only: the orders still under way first (with
 * where they are and what to do next), then the finished ones grouped by day (India time, like the other day lists).
 */

/**
 * Unpaid orders are cancelled this long after they are placed (the contract's payment window). Only for answers of
 * older servers: the list now carries the exact `payment.dueAt`.
 */
export const PAYMENT_WINDOW_MS = 15 * 60_000

/** @typedef {Pick<OrderSummary, 'status' | 'type' | 'store' | 'payment' | 'createdAt'>} Summary */

/**
 * Not finished yet: waiting for payment or the store, being packed or cooked, or on the way.
 * @param {Pick<OrderSummary, 'status'>} order
 * @returns {boolean}
 */
export function isActiveOrder(order) {
  return !isTerminalStatus(order.status)
}

/**
 * One page of orders split into the ones under way and the finished ones, each in the server's order (newest first),
 * except that orders waiting for the customer's payment lead the ones under way: their payment window runs out.
 * @template {Pick<OrderSummary, 'status' | 'payment'>} T
 * @param {readonly T[]} orders
 * @returns {{ active: T[], past: T[] }}
 */
export function splitActiveOrders(orders) {
  const active = []
  const past = []
  for (const order of orders) (isActiveOrder(order) ? active : past).push(order)
  const waitsForPayment = (order) => (orderNextAction(order).kind === 'pay' ? 0 : 1)
  return { active: active.sort((a, b) => waitsForPayment(a) - waitsForPayment(b)), past }
}

/**
 * The orders of one day, in the order the server sent them (newest first).
 * @template T
 * @typedef {object} OrderDay
 * @property {string} day `YYYY-MM-DD` (India time), or `''` when the order has no usable time.
 * @property {string} title "Today", "Yesterday" or the date ("Mon, 16 Nov 2026").
 * @property {string | null} subtitle The date under "Today" / "Yesterday"; null otherwise.
 * @property {T[]} orders
 */

/**
 * @param {string} at
 * @returns {string}
 */
function dayOf(at) {
  return Number.isNaN(new Date(at).getTime()) ? '' : indiaDay(at)
}

/**
 * "Today" / "Yesterday" with the date beside it, otherwise the date itself.
 * @param {string} day
 * @param {string} today
 * @returns {{ title: string, subtitle: string | null }}
 */
export function orderDayTitle(day, today) {
  if (day === '') return { title: 'Date unknown', subtitle: null }
  if (day === today) return { title: 'Today', subtitle: formatLongDay(day) }
  if (day === shiftDay(today, -1)) return { title: 'Yesterday', subtitle: formatLongDay(day) }
  return { title: formatLongDay(day), subtitle: null }
}

/**
 * A page of orders grouped by the day they were placed; a day keeps the place of its first order.
 * @template {Pick<OrderSummary, 'createdAt'>} T
 * @param {readonly T[]} orders
 * @param {string} [today=indiaDay()]
 * @returns {OrderDay<T>[]}
 */
export function groupOrdersByDay(orders, today = indiaDay()) {
  const days = new Map()
  for (const order of orders) {
    const day = dayOf(order.createdAt)
    let group = days.get(day)
    if (!group) {
      group = { day, ...orderDayTitle(day, today), orders: [] }
      days.set(day, group)
    }
    group.orders.push(order)
  }
  return [...days.values()]
}

/**
 * The end of the payment window as the server sends it (`payment.dueAt`); null when it sent none (older servers).
 * @param {Pick<OrderSummary, 'payment'>} order
 * @returns {number | null}
 */
export function exactPaymentDueAt(order) {
  const dueAt = order.payment.dueAt ? Date.parse(order.payment.dueAt) : Number.NaN
  return Number.isFinite(dueAt) ? dueAt : null
}

/**
 * Whole minutes left to pay (0 once the window has passed): until the server's `payment.dueAt`, or, from an older
 * server without it, an estimate (placed time + 15 minutes).
 * @param {Pick<OrderSummary, 'payment' | 'createdAt'>} order
 * @param {number} now
 * @returns {number}
 */
export function paymentMinutesLeft(order, now) {
  const dueAt = exactPaymentDueAt(order) ?? new Date(order.createdAt).getTime() + PAYMENT_WINDOW_MS
  if (Number.isNaN(dueAt)) return 0
  return Math.max(0, Math.ceil((dueAt - now) / 60_000))
}

/**
 * @typedef {object} ActiveOrderLine
 * @property {string} title The one thing to know now: "Pay within 12 min", "Being packed", "Arriving soon".
 * @property {string} hint A short sentence under it.
 * @property {StatusTone} tone
 * @property {IconName} icon
 */

/**
 * Where an order under way is, in the customer's words (the colour and picture match the order page).
 * @param {Summary} order
 * @param {number} now
 * @returns {ActiveOrderLine}
 */
export function activeOrderLine(order, now) {
  const look = orderStatusLook({ status: order.status, type: order.type, readyInMinutes: null, rejection: null })
  const line = (title, hint) => ({ title, hint, tone: look.tone, icon: look.icon })
  const grocery = order.type === 'GROCERY'
  const store = order.store.name
  switch (order.status) {
    case 'CREATED':
      return line('Checking stock…', `${store} is reserving your items.`)
    case 'AWAITING_PAYMENT': {
      if (order.payment.status === 'EXPIRED')
        return { ...line('Payment time ran out', 'This order will be cancelled.'), tone: 'danger' }
      const minutes = paymentMinutesLeft(order, now)
      if (exactPaymentDueAt(order) !== null) {
        // The server's own due time: once it has passed, the order is being cancelled.
        return minutes > 0
          ? line(`Pay within ${minutes} min`, `Pay by ${formatTime(order.payment.dueAt)}, or the order is cancelled.`)
          : { ...line('Payment time is up', 'This order is being cancelled.'), tone: 'danger' }
      }
      // Only an estimate (an older server): past it, the order may still be payable for a moment.
      return minutes > 0
        ? line(`Pay within ${minutes} min`, 'Unpaid orders are cancelled after 15 minutes.')
        : line('Payment window closing', 'Pay now, or the order is cancelled.')
    }
    case 'CONFIRMED':
      return line('Order confirmed', `Waiting for ${store} to accept it.`)
    case 'ACCEPTED':
      return line('Order accepted', grocery ? `${store} will start packing soon.` : `${store} will start cooking soon.`)
    case 'PREPARING':
      return grocery
        ? line('Being packed', `${store} is packing your order.`)
        : line('Being prepared', `${store} is preparing your food.`)
    case 'READY_FOR_PICKUP':
      return line('Ready for pickup', 'Waiting for the delivery partner to collect it.')
    case 'OUT_FOR_DELIVERY':
      return line('Arriving soon', 'Your delivery partner is on the way.')
    default:
      return line(ORDER_STATUS_LABELS[order.status], '')
  }
}

/** @typedef {'pay' | 'track' | 'view'} OrderActionKind */

/**
 * What opening an order under way is for: paying, following it, or just looking. With `now`, an order past the
 * server's payment due time is no longer for paying (it is being cancelled).
 * @param {Pick<OrderSummary, 'status' | 'payment'>} order
 * @param {number} [now]
 * @returns {{ kind: OrderActionKind, label: string }}
 */
export function orderNextAction(order, now) {
  if (
    order.status === 'AWAITING_PAYMENT' &&
    (order.payment.status === 'PENDING' || order.payment.status === 'NOT_STARTED')
  ) {
    const dueAt = exactPaymentDueAt(order)
    if (now === undefined || dueAt === null || now < dueAt) return { kind: 'pay', label: 'Pay now' }
  }
  if (
    order.status === 'ACCEPTED' ||
    order.status === 'PREPARING' ||
    order.status === 'READY_FOR_PICKUP' ||
    order.status === 'OUT_FOR_DELIVERY'
  ) {
    return { kind: 'track', label: 'Track order' }
  }
  return { kind: 'view', label: 'View order' }
}

/**
 * Placed → Confirmed → Packing / Preparing → On the way → Delivered for a list entry (no timeline in the list).
 * @param {Pick<OrderSummary, 'status' | 'type' | 'createdAt'>} order
 * @returns {ProgressStep[]}
 */
export function summaryProgress(order) {
  return orderProgress({ status: order.status, type: order.type, timeline: [], updatedAt: order.createdAt })
}

/**
 * Whether a finished order's row shows the payment next to the status: always, except the usual "Delivered, paid".
 * @param {Pick<OrderSummary, 'status' | 'payment'>} order
 * @returns {boolean}
 */
export function showsPaymentStatus(order) {
  return !(order.status === 'DELIVERED' && order.payment.status === 'PAID')
}

/**
 * `1 item` / `5 items` (the sum of the quantities, as the list gives it).
 * @param {number} count
 * @returns {string}
 */
export function itemsText(count) {
  return `${count} item${count === 1 ? '' : 's'}`
}

/**
 * `1 order` / `17 orders`.
 * @param {number} count
 * @returns {string}
 */
export function ordersText(count) {
  return `${count.toLocaleString('en-IN')} order${count === 1 ? '' : 's'}`
}
