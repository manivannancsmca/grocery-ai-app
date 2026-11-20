import { problemString } from '../../shared/api/problemDetail'
import type { BadgeTone } from '../../shared/components/ui'
import { humanizeToken } from '../../shared/utils/format'
import type { PurchaseOrderStatus } from './api'

export const PO_STATUS_LABELS: Record<PurchaseOrderStatus, string> = {
  DRAFT: 'Draft',
  SENT: 'Sent',
  PARTIALLY_RECEIVED: 'Partially received',
  RECEIVED: 'Received',
  CANCELLED: 'Cancelled',
  CLOSED: 'Closed (short)',
}

export const PO_STATUS_TONES: Record<PurchaseOrderStatus, BadgeTone> = {
  DRAFT: 'gray',
  SENT: 'blue',
  PARTIALLY_RECEIVED: 'amber',
  RECEIVED: 'green',
  CANCELLED: 'red',
  CLOSED: 'violet',
}

export function poStatusLabel(status: string): string {
  return status in PO_STATUS_LABELS ? PO_STATUS_LABELS[status as PurchaseOrderStatus] : humanizeToken(status)
}

export function poStatusTone(status: string): BadgeTone {
  return status in PO_STATUS_TONES ? PO_STATUS_TONES[status as PurchaseOrderStatus] : 'gray'
}

/** What may be done with a purchase order in each status (contract §4). */
export function poActions(status: string) {
  return {
    edit: status === 'DRAFT',
    send: status === 'DRAFT',
    cancel: status === 'DRAFT' || status === 'SENT',
    receive: status === 'SENT' || status === 'PARTIALLY_RECEIVED',
    close: status === 'PARTIALLY_RECEIVED',
  }
}

/** 409 INVALID_STATUS_TRANSITION in words, naming the status the order is in now. */
export function transitionConflictMessage(error: unknown): string {
  const current = problemString(error, 'currentStatus')
  return current
    ? `This purchase order is ${poStatusLabel(current).toLowerCase()} now, so that is no longer possible. It has been refreshed.`
    : 'The purchase order changed in the meantime, so that is no longer possible. It has been refreshed.'
}

/** `abc-uuid` key for one receipt submission: retries of the same submission reuse it. */
export function newIdempotencyKey(): string {
  if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') return crypto.randomUUID()
  return `receipt-${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 10)}`
}
