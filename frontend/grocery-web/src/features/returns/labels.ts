import { errorMessage, hasErrorCode, problemString } from '../../shared/api/problemDetail'
import type { BadgeTone } from '../../shared/components/ui'
import { formatDate, formatDateTime, formatPrice } from '../../shared/utils/format'
import type { OrderView } from '../orders/api'
import { RETURN_ERRORS, type ReturnReason, type ReturnStatus, type ReturnView } from './api'

export const RETURN_REASON_LABELS: Record<ReturnReason, string> = {
  DAMAGED: 'Damaged or spoiled',
  WRONG_ITEM: 'Wrong item',
  MISSING: 'Missing item',
  QUALITY: 'Poor quality',
  OTHER: 'Something else',
}

export function returnReasonLabel(reason: string): string {
  return reason in RETURN_REASON_LABELS ? RETURN_REASON_LABELS[reason as ReturnReason] : reason
}

/** The customer's view of a return's progress. */
export const RETURN_STATUS_LABELS: Record<ReturnStatus, string> = {
  REQUESTED: 'Requested',
  ESCALATED: 'With support',
  APPROVED: 'Approved',
  REFUNDED: 'Refunded',
  REJECTED: 'Rejected',
}

export const RETURN_STATUS_TONES: Record<ReturnStatus, BadgeTone> = {
  REQUESTED: 'amber',
  ESCALATED: 'violet',
  APPROVED: 'blue',
  REFUNDED: 'green',
  REJECTED: 'red',
}

export function returnStatusLabel(status: string): string {
  return status in RETURN_STATUS_LABELS ? RETURN_STATUS_LABELS[status as ReturnStatus] : status
}

export function returnStatusTone(status: string): BadgeTone {
  return status in RETURN_STATUS_TONES ? RETURN_STATUS_TONES[status as ReturnStatus] : 'gray'
}

/** REQUESTED or ESCALATED: still waiting for a decision (only one per order at a time). */
export function isOpenReturn(status: string): boolean {
  return status === 'REQUESTED' || status === 'ESCALATED'
}

/** Restaurant orders use the same flow, worded as "report a problem" (never restocked). */
export function returnActionLabel(orderType: string): string {
  return orderType === 'RESTAURANT' ? 'Report a problem' : 'Return items'
}

/** One sentence on where a return stands, for the customer. */
export function returnStatusText(view: Pick<ReturnView, 'status' | 'storeName' | 'decideBy' | 'refundAmount' | 'refundedAt' | 'comment'>): string {
  const refund = formatPrice(view.refundAmount)
  switch (view.status) {
    case 'REQUESTED':
      return `Waiting for ${view.storeName || 'the store'} to decide${view.decideBy ? ` (by ${formatDateTime(view.decideBy)})` : ''}. If approved, ${refund} will be refunded.`
    case 'ESCALATED':
      return `${view.storeName || 'The store'} did not decide in time, so our support team is looking at it. If approved, ${refund} will be refunded.`
    case 'APPROVED':
      return `Approved. ${refund} will be refunded to your original payment method.`
    case 'REFUNDED':
      return `${refund} was refunded to your original payment method${view.refundedAt ? ` on ${formatDate(view.refundedAt)}` : ''}.`
    case 'REJECTED':
      return view.comment ? `Rejected: “${view.comment}”` : 'Rejected.'
    default:
      return ''
  }
}

/**
 * How many of each line can still be asked back: the ordered quantity minus what other requests (except rejected
 * ones) already cover. Without per-line details in the order's return summaries the server checks it
 * (422 RETURN_QUANTITY_EXCEEDED).
 */
export function returnableQuantities(order: Pick<OrderView, 'items' | 'returns'>): Map<string, number> {
  const left = new Map(order.items.map((item) => [item.itemId, item.quantity]))
  for (const request of order.returns ?? []) {
    if (request.status === 'REJECTED') continue
    for (const line of request.items ?? []) {
      left.set(line.itemId, Math.max(0, (left.get(line.itemId) ?? 0) - line.quantity))
    }
  }
  return left
}

/** Friendly text for a refused return request. */
export function returnErrorMessage(error: unknown): string {
  if (hasErrorCode(error, RETURN_ERRORS.notAllowed)) {
    const until = problemString(error, 'returnableUntil')
    return until
      ? `The return window of this order ended on ${formatDateTime(until)}.`
      : 'A return can no longer be requested for this order: its return window has ended, or another request for it is still open.'
  }
  if (hasErrorCode(error, RETURN_ERRORS.quantityExceeded)) {
    const name = problemString(error, 'itemName') ?? problemString(error, 'name')
    return name
      ? `You asked to return more ${name} than is left to return. Lower the quantity.`
      : 'You asked to return more of an item than is left to return. Lower the quantities.'
  }
  return errorMessage(error)
}
