import { cancelledByLabel } from '../orders/labels'
import { STORE_KIND_CONFIG } from './api'

/** @typedef {import('../../shared/icons').IconName} IconName */
/** @typedef {import('./api').StoreKind} StoreKind */
/** @typedef {import('./api').StoreOrderCancellation} StoreOrderCancellation */
/** @typedef {import('./api').StoreOrderRejection} StoreOrderRejection */
/** @typedef {import('./api').StoreOrderStatus} StoreOrderStatus */
/** @typedef {import('./api').StoreOrderView} StoreOrderView */
/** @typedef {import('./api').StoreRejectReason} StoreRejectReason */

/**
 * Status words on the board; PREPARING reads "Packing" for groceries.
 * @param {StoreKind} kind
 * @param {string} status
 * @returns {string}
 */
export function storeOrderStatusLabel(kind, status) {
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

/**
 * The reject reasons a store user picks from.
 * @type {Record<StoreRejectReason, string>}
 */
export const STORE_REJECT_REASON_LABELS = {
  OUT_OF_STOCK: 'Items out of stock',
  STORE_CLOSED: 'Store closed',
  TOO_BUSY: 'Too busy right now',
  OTHER: 'Other (add a note)',
}

/**
 * The four board columns, in order.
 * @type {readonly StoreOrderStatus[]}
 */
export const BOARD_COLUMNS = ['NEW', 'ACCEPTED', 'PREPARING', 'READY_FOR_PICKUP']

/**
 * Column titles: New · Accepted · Preparing (Packing) · Ready.
 * @param {StoreKind} kind
 * @param {StoreOrderStatus} status
 * @returns {string}
 */
export function boardColumnTitle(kind, status) {
  return status === 'READY_FOR_PICKUP' ? 'Ready' : storeOrderStatusLabel(kind, status)
}

/**
 * @param {StoreKind} kind
 * @param {StoreOrderStatus} status
 * @returns {string}
 */
export function boardColumnEmptyText(kind, status) {
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

/**
 * How a board column (and its orders' step) looks: its icon and colours (class names spelled out so Tailwind keeps them).
 * @typedef {object} BoardColumnStyle
 * @property {IconName} icon
 * @property {string} dot The status dot in the column header and the phone switcher, and the filled steps of a card.
 * @property {string} count The count pill when the column has orders.
 * @property {string} tile The icon tile in the column header and the empty state.
 */

/**
 * @param {StoreKind} kind
 * @param {StoreOrderStatus} status
 * @returns {BoardColumnStyle}
 */
export function boardColumnStyle(kind, status) {
  switch (status) {
    case 'NEW':
      return { icon: 'bell', dot: 'bg-amber-500', count: 'bg-amber-500 text-white', tile: 'bg-amber-50 ring-amber-200' }
    case 'ACCEPTED':
      return { icon: 'checkCircle', dot: 'bg-sky-500', count: 'bg-sky-600 text-white', tile: 'bg-sky-50 ring-sky-200' }
    case 'PREPARING':
      return {
        icon: kind === 'VENDOR' ? 'box' : 'cooking',
        dot: 'bg-violet-500',
        count: 'bg-violet-600 text-white',
        tile: 'bg-violet-50 ring-violet-200',
      }
    default:
      return {
        icon: 'bag',
        dot: 'bg-emerald-500',
        count: 'bg-emerald-600 text-white',
        tile: 'bg-emerald-50 ring-emerald-200',
      }
  }
}

/**
 * The tone of a "Closed today" row's status chip.
 * @typedef {'done' | 'rejected' | 'cancelled'} ClosedTone
 */

/**
 * A "Closed today" row: its status chip and the reason, which reads on from the chip ("Rejected" "items out of stock").
 * @typedef {object} ClosedOrderSummary
 * @property {ClosedTone} tone
 * @property {string} chip
 * @property {string | null} reason
 * @property {string | null} note The store's own words to the customer (a rejection note).
 */

/** @type {Record<string, string>} */
const REJECTION_REASONS = {
  OUT_OF_STOCK: 'items out of stock',
  STORE_CLOSED: 'store closed',
  TOO_BUSY: 'too busy',
}

/**
 * @param {StoreOrderRejection} rejection
 * @returns {ClosedOrderSummary}
 */
function rejectionSummary(rejection) {
  const note = rejection.note?.trim() || null
  if (rejection.auto || rejection.reason === 'NO_RESPONSE')
    return { tone: 'rejected', chip: 'Auto-rejected', reason: 'not accepted in time', note }
  const reason =
    rejection.reason === 'OTHER'
      ? null
      : (REJECTION_REASONS[rejection.reason] ?? rejection.reason.toLowerCase().replaceAll('_', ' '))
  return { tone: 'rejected', chip: 'Rejected', reason, note }
}

/**
 * @param {StoreOrderCancellation | null} cancellation
 * @returns {ClosedOrderSummary}
 */
function cancellationSummary(cancellation) {
  const by = cancellation?.by
  const reason = !by
    ? null
    : by === 'CUSTOMER'
      ? 'by the customer'
      : by === 'ADMIN'
        ? 'by support'
        : `by ${cancelledByLabel(by)}`
  return { tone: 'cancelled', chip: 'Cancelled', reason, note: null }
}

/**
 * What happened to an order on the "Closed today" list: picked up, (auto-)rejected and why, or cancelled and by whom.
 * @param {StoreKind} kind
 * @param {Pick<StoreOrderView, 'status' | 'rejection' | 'cancellation'>} order
 * @returns {ClosedOrderSummary}
 */
export function closedOrderSummary(kind, order) {
  if (order.status === 'PICKED_UP')
    return { tone: 'done', chip: 'Picked up', reason: 'by the delivery partner', note: null }
  if (order.rejection) return rejectionSummary(order.rejection)
  if (order.status === 'CANCELLED' || order.cancellation) return cancellationSummary(order.cancellation)
  return { tone: 'cancelled', chip: storeOrderStatusLabel(kind, order.status), reason: null, note: null }
}
