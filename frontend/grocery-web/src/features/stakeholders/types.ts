import type { BadgeTone } from '../../shared/components/ui'

/**
 * Shared onboarding lifecycle of vendors, restaurants and delivery partners (contract §5):
 * DRAFT → PENDING_APPROVAL → APPROVED ⇄ SUSPENDED, PENDING_APPROVAL → REJECTED → (edit) → submit again.
 */
export const STAKEHOLDER_STATUSES = ['DRAFT', 'PENDING_APPROVAL', 'APPROVED', 'REJECTED', 'SUSPENDED'] as const
export type StakeholderStatus = (typeof STAKEHOLDER_STATUSES)[number]

export const STATUS_LABELS: Record<StakeholderStatus, string> = {
  DRAFT: 'Draft',
  PENDING_APPROVAL: 'Pending approval',
  APPROVED: 'Approved',
  REJECTED: 'Rejected',
  SUSPENDED: 'Suspended',
}

export const STATUS_TONES: Record<StakeholderStatus, BadgeTone> = {
  DRAFT: 'gray',
  PENDING_APPROVAL: 'amber',
  APPROVED: 'green',
  REJECTED: 'red',
  SUSPENDED: 'violet',
}

export function isStakeholderStatus(value: unknown): value is StakeholderStatus {
  return typeof value === 'string' && (STAKEHOLDER_STATUSES as readonly string[]).includes(value)
}

/** The owner may edit (PUT) only in these states; otherwise the API answers `409 PROFILE_LOCKED`. */
export function isEditableStatus(status: StakeholderStatus): boolean {
  return status === 'DRAFT' || status === 'REJECTED'
}

/** Business address of a vendor or restaurant. */
export interface BusinessAddress {
  line1: string | null
  line2?: string | null
  city: string | null
  state: string | null
  postalCode: string | null
  country: string | null
  latitude: number | null
  longitude: number | null
}

/** Lifecycle fields every stakeholder view carries. */
export interface StakeholderLifecycle {
  id: string
  status: StakeholderStatus
  /** Latest rejection / suspension reason, null otherwise. */
  statusReason: string | null
  submittedAt: string | null
  approvedAt: string | null
  createdAt: string
  updatedAt: string
}

/** One entry of `GET /api/admin/<type>/{id}/history` (newest first). */
export interface StatusChange {
  fromStatus: StakeholderStatus | null
  toStatus: StakeholderStatus
  reason: string | null
  changedBy: string
  changedAt: string
}

export interface StakeholderListParams {
  status?: StakeholderStatus
  query?: string
  page?: number
  size?: number
}
