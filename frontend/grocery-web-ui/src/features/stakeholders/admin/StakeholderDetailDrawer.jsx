import { useState } from 'react'
import { ApiError, errorMessage } from '../../../shared/api/problemDetail'
import { useAuth } from '../../../shared/auth/useAuth'
import { ReasonDialog } from '../../../shared/components/ReasonDialog'
import { useToast } from '../../../shared/components/toast/useToast'
import {
  Alert,
  Button,
  ConfirmDialog,
  DescriptionList,
  Dialog,
  ErrorState,
  Spinner,
} from '../../../shared/components/ui'
import { formatDateTime } from '../../../shared/utils/format'
import { StatusBadge } from '../StatusBadge'
import { useStakeholderAction, useStakeholderDetail, useStakeholderHistory } from './hooks'
import { StatusHistory } from './StatusHistory'

/** @typedef {import('../../../shared/icons').IconName} IconName */
/** @typedef {import('../types').StakeholderLifecycle} StakeholderLifecycle */
/** @typedef {import('./kind').StakeholderAction} StakeholderAction */
/** @typedef {import('./kind').StakeholderKind} StakeholderKind */

/**
 * The drawer's picture for each kind of partner: the same as its admin menu item.
 * @type {Record<StakeholderKind<StakeholderLifecycle>['slug'], IconName>}
 */
const KIND_ICONS = {
  vendors: 'store',
  restaurants: 'cloche',
  'delivery-partners': 'scooter',
}

/**
 * @template {StakeholderLifecycle} T
 * @typedef {object} StakeholderDetailDrawerProps
 * @property {StakeholderKind<T>} kind
 * @property {string} id
 * @property {T} [initial] The row the admin clicked, shown while the full record loads.
 * @property {() => void} onClose
 */

/** @type {Record<StakeholderAction, string>} */
const SUCCESS_MESSAGES = {
  approve: 'approved',
  reject: 'rejected',
  suspend: 'suspended',
  reinstate: 'reinstated',
}

/**
 * Details, status history and lifecycle actions for one vendor / restaurant / delivery partner.
 * @template {StakeholderLifecycle} T
 * @param {StakeholderDetailDrawerProps<T>} props
 */
export function StakeholderDetailDrawer({ kind, id, initial, onClose }) {
  const { can } = useAuth()
  const toast = useToast()
  const detail = useStakeholderDetail(kind, id)
  const history = useStakeholderHistory(kind, id)
  const action = useStakeholderAction(kind)
  const [reasonAction, setReasonAction] = useState(null)
  const [confirmReinstate, setConfirmReinstate] = useState(false)
  const [actionError, setActionError] = useState(null)

  // Show the clicked row immediately; the fresh record replaces it once loaded.
  const item = detail.data ?? initial
  const name = item ? kind.displayName(item) : `this ${kind.singular}`
  const canManage = can(kind.managePermission)

  async function perform(type, reason) {
    setActionError(null)
    await action.mutateAsync({ action: type, id, reason })
    toast.success(`${name} ${SUCCESS_MESSAGES[type]}.`)
    if (type === 'approve' || type === 'reject') onClose()
  }

  function performWithFeedback(type) {
    perform(type).catch((error) => {
      setActionError(errorMessage(error))
      setConfirmReinstate(false)
    })
  }

  const footer =
    item && canManage ? (
      <>
        {item.status === 'PENDING_APPROVAL' && (
          <>
            <Button variant="danger" onClick={() => setReasonAction('reject')} disabled={action.isPending}>
              Reject
            </Button>
            <Button onClick={() => performWithFeedback('approve')} disabled={action.isPending}>
              {action.isPending && action.variables.action === 'approve' && (
                <Spinner size="sm" className="[&_svg]:text-white" />
              )}
              Approve
            </Button>
          </>
        )}
        {item.status === 'APPROVED' && (
          <Button variant="danger" onClick={() => setReasonAction('suspend')} disabled={action.isPending}>
            Suspend
          </Button>
        )}
        {item.status === 'SUSPENDED' && (
          <Button onClick={() => setConfirmReinstate(true)} disabled={action.isPending}>
            Reinstate
          </Button>
        )}
      </>
    ) : undefined

  return (
    <>
      <Dialog
        open
        variant="drawer"
        icon={KIND_ICONS[kind.slug]}
        onClose={onClose}
        title={item ? kind.displayName(item) : `Loading ${kind.singular}…`}
        description={item ? <StatusBadge status={item.status} /> : undefined}
        footer={footer}
      >
        {!item && detail.isPending ? (
          <div className="flex justify-center py-10">
            <Spinner size="lg" label={`Loading ${kind.singular}`} />
          </div>
        ) : !item && detail.isError ? (
          <ErrorState
            message={detail.error.message}
            correlationId={detail.error instanceof ApiError ? detail.error.correlationId : null}
            onRetry={() => void detail.refetch()}
          />
        ) : item ? (
          <div className="space-y-6">
            {actionError && (
              <Alert tone="danger" role="alert">
                {actionError}
              </Alert>
            )}
            {item.statusReason && (item.status === 'REJECTED' || item.status === 'SUSPENDED') && (
              <Alert tone="warning" title={item.status === 'REJECTED' ? 'Rejection reason' : 'Suspension reason'}>
                {item.statusReason}
              </Alert>
            )}
            {!canManage &&
              (item.status === 'PENDING_APPROVAL' || item.status === 'APPROVED' || item.status === 'SUSPENDED') && (
                <p className="rounded-lg bg-slate-50 px-3 py-2 text-xs text-slate-500">
                  Your admin role can view {kind.plural.toLowerCase()} but not change their status.
                </p>
              )}
            <section>
              <h3 className="mb-3 text-sm font-semibold text-slate-900">Details</h3>
              <DescriptionList items={kind.details(item)} />
            </section>
            {kind.extraSection && (
              <section>
                <h3 className="mb-3 text-sm font-semibold text-slate-900">{kind.extraSection.title}</h3>
                {kind.extraSection.render(item)}
              </section>
            )}
            <section>
              <h3 className="mb-3 text-sm font-semibold text-slate-900">Timeline</h3>
              <DescriptionList
                items={[
                  { label: 'Created', value: formatDateTime(item.createdAt) },
                  { label: 'Submitted', value: item.submittedAt ? formatDateTime(item.submittedAt) : null },
                  { label: 'Approved', value: item.approvedAt ? formatDateTime(item.approvedAt) : null },
                  { label: 'Last updated', value: formatDateTime(item.updatedAt) },
                ]}
              />
            </section>
            <section>
              <h3 className="mb-3 text-sm font-semibold text-slate-900">Status history</h3>
              <StatusHistory changes={history.data} isPending={history.isPending} error={history.error} />
            </section>
          </div>
        ) : null}
      </Dialog>

      <ReasonDialog
        open={reasonAction !== null}
        title={reasonAction === 'reject' ? `Reject ${name}` : `Suspend ${name}`}
        description={
          reasonAction === 'reject'
            ? `The ${kind.singular} sees this reason and can fix the details and submit again.`
            : `The ${kind.singular} is blocked immediately and sees this reason.`
        }
        confirmLabel={reasonAction === 'reject' ? 'Reject application' : `Suspend ${kind.singular}`}
        onClose={() => setReasonAction(null)}
        onSubmit={async (reason) => {
          if (!reasonAction) return
          await perform(reasonAction, reason)
          setReasonAction(null)
        }}
      />

      <ConfirmDialog
        open={confirmReinstate}
        title={`Reinstate ${name}?`}
        confirmLabel="Reinstate"
        pending={action.isPending}
        onClose={() => setConfirmReinstate(false)}
        onConfirm={() => {
          perform('reinstate')
            .then(() => setConfirmReinstate(false))
            .catch((error) => {
              setActionError(errorMessage(error))
              setConfirmReinstate(false)
            })
        }}
      >
        <p>The {kind.singular} becomes active again and can operate on FreshCart.</p>
      </ConfirmDialog>
    </>
  )
}
