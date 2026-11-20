/** @typedef {import('../../shared/components/ui').BadgeTone} BadgeTone */

/**
 * Shared onboarding lifecycle of vendors, restaurants and delivery partners (contract §5):
 * DRAFT → PENDING_APPROVAL → APPROVED ⇄ SUSPENDED, PENDING_APPROVAL → REJECTED → (edit) → submit again.
 */
export const STAKEHOLDER_STATUSES = ['DRAFT', 'PENDING_APPROVAL', 'APPROVED', 'REJECTED', 'SUSPENDED']
/** @typedef {(typeof STAKEHOLDER_STATUSES)[number]} StakeholderStatus */

/** @type {Record<StakeholderStatus, string>} */
export const STATUS_LABELS = {
  DRAFT: 'Draft',
  PENDING_APPROVAL: 'Pending approval',
  APPROVED: 'Approved',
  REJECTED: 'Rejected',
  SUSPENDED: 'Suspended',
}

/** @type {Record<StakeholderStatus, BadgeTone>} */
export const STATUS_TONES = {
  DRAFT: 'gray',
  PENDING_APPROVAL: 'amber',
  APPROVED: 'green',
  REJECTED: 'red',
  SUSPENDED: 'violet',
}

/**
 * @param {unknown} value
 * @returns {value is StakeholderStatus}
 */
export function isStakeholderStatus(value) {
  return typeof value === 'string' && STAKEHOLDER_STATUSES.includes(value)
}

/**
 * The owner may edit (PUT) only in these states; otherwise the API answers `409 PROFILE_LOCKED`.
 * @param {StakeholderStatus} status
 * @returns {boolean}
 */
export function isEditableStatus(status) {
  return status === 'DRAFT' || status === 'REJECTED'
}

/**
 * Business address of a vendor or restaurant.
 * @typedef {object} BusinessAddress
 * @property {string | null} line1
 * @property {string | null} [line2]
 * @property {string | null} city
 * @property {string | null} state
 * @property {string | null} postalCode
 * @property {string | null} country
 * @property {number | null} latitude
 * @property {number | null} longitude
 */

/**
 * Lifecycle fields every stakeholder view carries.
 * @typedef {object} StakeholderLifecycle
 * @property {string} id
 * @property {StakeholderStatus} status
 * @property {string | null} statusReason Latest rejection / suspension reason, null otherwise.
 * @property {string | null} submittedAt
 * @property {string | null} approvedAt
 * @property {string} createdAt
 * @property {string} updatedAt
 */

/**
 * One entry of `GET /api/admin/<type>/{id}/history` (newest first).
 * @typedef {object} StatusChange
 * @property {StakeholderStatus | null} fromStatus
 * @property {StakeholderStatus} toStatus
 * @property {string | null} reason
 * @property {string} changedBy
 * @property {string} changedAt
 */

/**
 * @typedef {object} StakeholderListParams
 * @property {StakeholderStatus} [status]
 * @property {string} [query]
 * @property {number} [page]
 * @property {number} [size]
 */
