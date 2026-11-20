import { problemString } from '../../shared/api/problemDetail'
import { roleLabel } from '../../shared/auth/roles'
import type { BadgeTone } from '../../shared/components/ui'
import { humanizeToken } from '../../shared/utils/format'
import type { ReturnReason, ReturnStatus } from './api'

export const RETURN_STATUS_LABELS: Record<ReturnStatus, string> = {
  REQUESTED: 'Waiting for decision',
  ESCALATED: 'Escalated to admins',
  APPROVED: 'Approved — refunding',
  REFUNDED: 'Refunded',
  REJECTED: 'Rejected',
}

export const RETURN_STATUS_TONES: Record<ReturnStatus, BadgeTone> = {
  REQUESTED: 'amber',
  ESCALATED: 'red',
  APPROVED: 'blue',
  REFUNDED: 'green',
  REJECTED: 'gray',
}

export const RETURN_REASON_LABELS: Record<ReturnReason, string> = {
  DAMAGED: 'Damaged',
  WRONG_ITEM: 'Wrong item',
  MISSING: 'Missing',
  QUALITY: 'Quality problem',
  OTHER: 'Other',
}

export function returnStatusLabel(status: string): string {
  return status in RETURN_STATUS_LABELS ? RETURN_STATUS_LABELS[status as ReturnStatus] : humanizeToken(status)
}

export function returnStatusTone(status: string): BadgeTone {
  return status in RETURN_STATUS_TONES ? RETURN_STATUS_TONES[status as ReturnStatus] : 'gray'
}

export function returnReasonLabel(reason: string): string {
  return reason in RETURN_REASON_LABELS ? RETURN_REASON_LABELS[reason as ReturnReason] : humanizeToken(reason)
}

/** "the store" / "Support" … */
export function deciderLabel(role: string | null): string | null {
  if (!role) return null
  if (role === 'VENDOR' || role === 'RESTAURANT_OWNER' || role === 'STORE') return 'the store'
  return roleLabel(role)
}

/** 409 RETURN_NOT_PENDING in words, naming the status the return is in now. */
export function returnConflictMessage(returnNumber: string, error: unknown): string {
  const current = problemString(error, 'currentStatus')
  const now = current ? ` (now: ${returnStatusLabel(current).toLowerCase()})` : ''
  return `Return ${returnNumber} was already decided${now}, so nothing was changed. The list has been refreshed.`
}
