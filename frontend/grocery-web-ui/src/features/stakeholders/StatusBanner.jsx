import { Alert } from '../../shared/components/ui'
import { formatDateTime } from '../../shared/utils/format'

/** @typedef {import('../../shared/components/ui').AlertTone} AlertTone */
/** @typedef {import('./types').StakeholderLifecycle} StakeholderLifecycle */
/** @typedef {import('./types').StakeholderStatus} StakeholderStatus */

/**
 * @typedef {object} StatusBannerProps
 * @property {Pick<StakeholderLifecycle, 'status' | 'statusReason' | 'submittedAt' | 'approvedAt'>} profile
 * @property {string} noun "business", "restaurant", "partner profile" …
 */

/** @type {Record<StakeholderStatus, AlertTone>} */
const TONES = {
  DRAFT: 'info',
  PENDING_APPROVAL: 'warning',
  APPROVED: 'success',
  REJECTED: 'danger',
  SUSPENDED: 'danger',
}

/**
 * Explains where the onboarding stands and what the owner should do next.
 * @param {StatusBannerProps} props
 */
export function StatusBanner({ profile, noun }) {
  const { status, statusReason, submittedAt, approvedAt } = profile
  const reason = statusReason?.trim() || 'No reason was given.'

  const content = {
    DRAFT: {
      title: 'Complete your onboarding',
      body: `Fill in your ${noun} details and submit them for approval. You can save a draft at any time.`,
    },
    PENDING_APPROVAL: {
      title: 'Waiting for approval',
      body: `Submitted ${formatDateTime(submittedAt)}. Our team is reviewing your ${noun}; this page updates automatically. Details are locked while under review.`,
    },
    APPROVED: {
      title: 'Approved',
      body: `Your ${noun} was approved ${formatDateTime(approvedAt)}. Contact support to change verified details.`,
    },
    REJECTED: {
      title: 'Changes needed',
      body: `Your application was rejected: ${reason} Update the details below and submit again.`,
    },
    SUSPENDED: {
      title: 'Suspended',
      body: `Your ${noun} is suspended: ${reason} Contact support to resolve it.`,
    },
  }

  const { title, body } = content[status]
  return (
    <Alert tone={TONES[status]} title={title} role="status">
      {body}
    </Alert>
  )
}
