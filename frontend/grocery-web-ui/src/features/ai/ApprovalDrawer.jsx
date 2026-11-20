import { ApiError } from '../../shared/api/problemDetail'
import { useAuth } from '../../shared/auth/useAuth'
import { DescriptionList, Dialog, ErrorState, Spinner } from '../../shared/components/ui'
import { formatDateTime } from '../../shared/utils/format'
import { AI_ERRORS } from './api'
import { ApprovalDetails, ApprovalStatusBadge } from './ApprovalCard'
import { useApproval } from './hooks'
import { agentLabel, approvalActionLabel, rolesLabel } from './labels'

/** @typedef {import('./api').ApprovalView} ApprovalView */

/** @param {{ value: string | null | undefined }} props */
function Mono({ value }) {
  return value ? <span className="font-mono text-xs break-all">{value}</span> : null
}

/** @param {{ view: ApprovalView, admin: boolean }} props */
function Bookkeeping({ view, admin }) {
  return (
    <section>
      <h3 className="mb-3 text-sm font-semibold text-slate-900">Details</h3>
      <DescriptionList
        items={[
          { label: 'Action', value: approvalActionLabel(view.action) },
          { label: 'Assistant', value: agentLabel(view.agent) },
          { label: 'Requested', value: formatDateTime(view.createdAt) },
          { label: 'Expires', value: formatDateTime(view.expiresAt) },
          ...(admin
            ? [
                { label: 'Requested by', value: <Mono value={view.requestedBy} /> },
                { label: 'For customer', value: <Mono value={view.onBehalfOf} /> },
                {
                  label: 'Decided by',
                  value: view.approverUserId ? (
                    <Mono value={view.approverUserId} />
                  ) : (
                    rolesLabel(view.approverRoles) || null
                  ),
                },
                { label: 'Four-eyes', value: view.fourEyes ? 'Yes — the requester may not decide' : 'No' },
              ]
            : []),
          { label: 'Decided', value: view.decidedAt ? formatDateTime(view.decidedAt) : null },
          ...(admin ? [{ label: 'Decider', value: <Mono value={view.decidedBy} /> }] : []),
          { label: 'Comment', value: view.comment, wide: true },
          { label: 'Executed', value: view.executedAt ? formatDateTime(view.executedAt) : null },
          { label: 'Fingerprint', value: <Mono value={view.fingerprint} /> },
          { label: 'Request ID', value: <Mono value={view.id} />, wide: true },
          ...(admin ? [{ label: 'Conversation', value: <Mono value={view.conversationId} />, wide: true }] : []),
        ]}
      />
    </section>
  )
}

/**
 * @typedef {object} ApprovalDrawerProps
 * @property {string} id
 * @property {ApprovalView} [initial] The clicked row: shown at once.
 * @property {() => void} onClose
 */

/**
 * One approval request (`GET /api/approvals/{id}`), with the decision actions and the bookkeeping.
 * @param {ApprovalDrawerProps} props
 */
export function ApprovalDrawer({ id, initial, onClose }) {
  const { area } = useAuth()
  const approval = useApproval(id, initial)
  const view = approval.data
  const admin = area === 'ADMIN'

  return (
    <Dialog
      open
      variant="drawer"
      icon="sparkleCheck"
      onClose={onClose}
      title={view?.summary.title ?? 'Approval request'}
      description={view ? <ApprovalStatusBadge status={view.status} /> : undefined}
    >
      {approval.isPending ? (
        <div className="flex justify-center py-12">
          <Spinner size="lg" label="Loading the request" />
        </div>
      ) : approval.isError && !view ? (
        <ErrorState
          title={
            approval.error instanceof ApiError && approval.error.errorCode === AI_ERRORS.approvalNotFound
              ? 'Request not found'
              : 'Could not load the request'
          }
          message={approval.error.message}
          correlationId={approval.error instanceof ApiError ? approval.error.correlationId : null}
          onRetry={() => void approval.refetch()}
        />
      ) : view ? (
        <div className="space-y-6">
          <ApprovalDetails view={view} />
          <Bookkeeping view={view} admin={admin} />
        </div>
      ) : null}
    </Dialog>
  )
}
