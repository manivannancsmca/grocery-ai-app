/** Small helpers shared by the order list cards: links and accessible descriptions. */

import { formatDateTime, formatPrice } from '../../shared/utils/format'
import { ORDER_PAYMENT_STATUS_LABELS, ORDER_STATUS_LABELS, orderItemsSummary } from './labels'

/** @typedef {import('./api').OrderSummary} OrderSummary */

/**
 * The customer's orders list: a highlighted strip of the orders still under way (where each one is and what to do),
 * then the finished ones grouped by day as compact rows. Every card and row is one link to the order's page.
 */

export const LINK_FOCUS = 'focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-emerald-600'

/**
 * @param {OrderSummary} order
 * @returns {string}
 */
export function orderPath(order) {
  return `/orders/${encodeURIComponent(order.id)}`
}

/**
 * What a screen reader hears after the link's name: total, status, payment, items and when it was placed.
 * @param {OrderSummary} order
 * @param {string} [lead]
 * @returns {string}
 */
export function orderDescription(order, lead) {
  return [
    lead,
    formatPrice(order.total),
    ORDER_STATUS_LABELS[order.status],
    ORDER_PAYMENT_STATUS_LABELS[order.payment.status],
    orderItemsSummary(order.firstItemName, order.itemCount),
    `placed ${formatDateTime(order.createdAt)}`,
  ]
    .filter(Boolean)
    .join(', ')
}
