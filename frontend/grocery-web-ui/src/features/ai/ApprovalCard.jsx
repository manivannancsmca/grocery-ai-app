import { useId, useState } from 'react'
import { errorMessage } from '../../shared/api/problemDetail'
import { useAuth } from '../../shared/auth/useAuth'
import { ReasonDialog } from '../../shared/components/ReasonDialog'
import { Alert, Badge, Button, ConfirmDialog, Spinner } from '../../shared/components/ui'
import { useNow } from '../../shared/hooks/useNow'
import { cn } from '../../shared/utils/cn'
import { formatCountdown, formatDateTime, formatMoney } from '../../shared/utils/format'
import { AI_LIMITS, isSelfDecidedAction } from './api'
import { useApproval, useApprovalDecision } from './hooks'
import {
  approvalActionLabel,
  approvalConfirmLabel,
  approvalStatusLabel,
  approvalStatusTone,
  decisionErrorMessage,
  isPending,
  NOT_NOW_COMMENT,
  rolesLabel,
} from './labels'
import { Outcome } from './ApprovalOutcome'
import { ApproveDialog } from './ApproveDialog'

/** @typedef {import('./api').ApprovalView} ApprovalView */

const HOUR_MS = 3_600_000

/**
 * Under an hour `12:34`; longer `5 h 20 min`.
 * @param {number} ms
 * @returns {string}
 */
function formatTimeLeft(ms) {
  if (ms < HOUR_MS) return formatCountdown(ms)
  const minutes = Math.floor(ms / 60_000)
  const hours = Math.floor(minutes / 60)
  const rest = minutes % 60
  return rest === 0 ? `${hours} h` : `${hours} h ${rest} min`
}

/** @param {{ status: string }} props */
export function ApprovalStatusBadge({ status }) {
  return (
    <Badge tone={approvalStatusTone(status)} dot>
      {approvalStatusLabel(status)}
    </Badge>
  )
}

/** @param {{ expiresAt: string, now: number }} props */
function ExpiryNote({ expiresAt, now }) {
  const left = Date.parse(expiresAt) - now
  if (!Number.isFinite(left)) return null
  return (
    <p className={cn('text-xs tabular-nums', left < 5 * 60_000 ? 'font-medium text-amber-700' : 'text-slate-500')}>
      <time dateTime={expiresAt} title={formatDateTime(expiresAt)}>
        Expires in {formatTimeLeft(left)}
      </time>
    </p>
  )
}

/**
 * @typedef {object} ApprovalDetailsProps
 * @property {ApprovalView} view
 * @property {boolean} [compact] Inside the chat: tighter spacing.
 */

/**
 * An approval request as the server describes it — title, summary lines, amount, fingerprint, the assistant's
 * note, the countdown — with the actions the signed-in person may take:
 * - the customer's own order action: "Confirm order" (approve) or "Not now" (reject);
 * - an eligible admin (`canDecide`): Approve (optional comment) or Reject (comment required);
 * - the requester: "Cancel request" while it is pending.
 * After a decision it shows the outcome: the placed order (with a link), the failure, the rejection.
 * @param {ApprovalDetailsProps} props
 */
export function ApprovalDetails({ view, compact = false }) {
  const titleId = useId()
  const { user, area } = useAuth()
  const decision = useApprovalDecision()
  const pending = isPending(view)
  const now = useNow(1_000, pending)
  const [dialog, setDialog] = useState(null)
  const [conflict, setConflict] = useState(null)
  const [cancelError, setCancelError] = useState(null)

  const admin = area === 'ADMIN'
  const expiredLocally = pending && Date.parse(view.expiresAt) <= now
  const open = pending && !expiredLocally
  // The customer's — or (Phase 12) the vendor's or restaurant owner's — own request: "Confirm" / "Not now".
  const customerDecision = !admin && isSelfDecidedAction(view.action) && view.canDecide
  const requester = user !== null && (view.requestedBy === user.id || view.onBehalfOf === user.id)
  const canCancel = open && requester && !customerDecision
  const busy = decision.isPending
  const busyKind = busy ? decision.variables?.kind : undefined
  const currency = view.summary.currency ?? 'INR'

  async function decide(kind, comment) {
    setConflict(null)
    try {
      await decision.mutateAsync({ id: view.id, kind, comment })
      setDialog(null)
    } catch (error) {
      const known = decisionErrorMessage(error)
      if (known) {
        setDialog(null)
        setConflict(known)
        return
      }
      // Inside a dialog the dialog shows it; a direct action shows it on the card.
      if (dialog === null) setConflict(errorMessage(error))
      else throw error
    }
  }

  async function cancelRequest() {
    setCancelError(null)
    try {
      await decide('cancel')
    } catch (error) {
      setCancelError(errorMessage(error))
    }
  }

  const confirmLabel = approvalConfirmLabel(view.action)

  return (
    <section
      aria-labelledby={titleId}
      className={cn(
        'rounded-xl border bg-white shadow-sm',
        compact ? 'p-3' : 'p-4',
        open
          ? 'border-amber-300'
          : view.status === 'FAILED'
            ? 'border-red-200'
            : view.status === 'COMPLETED'
              ? 'border-emerald-200'
              : 'border-slate-200',
      )}
    >
      <div className="flex flex-wrap items-center justify-between gap-2">
        <p className="text-xs font-semibold tracking-wide text-slate-500 uppercase">
          {approvalActionLabel(view.action)}
        </p>
        <span className="flex flex-wrap items-center gap-1.5">
          {view.fourEyes && (
            <Badge tone="violet" className="cursor-help">
              <span title="The requester may not decide it: a second admin has to.">Four-eyes</span>
            </Badge>
          )}
          {expiredLocally ? <ApprovalStatusBadge status="EXPIRED" /> : <ApprovalStatusBadge status={view.status} />}
        </span>
      </div>
      <h3 id={titleId} className="mt-1 text-sm font-semibold break-words text-slate-900">
        {view.summary.title}
      </h3>

      {view.summary.lines.length > 0 && (
        <dl className="mt-2 divide-y divide-slate-100 text-sm">
          {view.summary.lines.map((line, index) => (
            <div key={`${line.label}-${index}`} className="flex items-start justify-between gap-3 py-1">
              <dt className="min-w-0 break-words text-slate-600">{line.label}</dt>
              <dd className="shrink-0 text-right text-slate-900 tabular-nums">{line.value}</dd>
            </div>
          ))}
        </dl>
      )}
      <dl className="mt-2 space-y-1 text-sm">
        {view.summary.amount !== null && (
          <div className="flex items-center justify-between gap-3 border-t border-slate-200 pt-2">
            <dt className="font-semibold text-slate-900">Amount</dt>
            <dd className="font-semibold text-slate-900 tabular-nums">{formatMoney(view.summary.amount, currency)}</dd>
          </div>
        )}
        <div className="flex items-center justify-between gap-3 text-xs text-slate-500">
          <dt title="The first characters of the exact request's SHA-256. The service that runs it checks the request is unchanged.">
            Fingerprint
          </dt>
          <dd className="font-mono text-slate-700">{view.fingerprint}</dd>
        </div>
      </dl>

      {view.rationale && (
        <div className="mt-2 rounded-lg bg-slate-50 px-3 py-2 text-xs text-slate-600">
          <p className="font-medium text-slate-700">The assistant’s note</p>
          <p className="mt-0.5 break-words whitespace-pre-wrap">{view.rationale}</p>
        </div>
      )}

      {open && (
        <div className="mt-2">
          <ExpiryNote expiresAt={view.expiresAt} now={now} />
        </div>
      )}
      {expiredLocally && (
        <p className="mt-2 text-xs text-slate-500">Nobody decided in time, so nothing will be done.</p>
      )}

      {!pending && (
        <div className="mt-3">
          <Outcome view={view} admin={admin} />
        </div>
      )}

      {conflict && (
        <Alert tone="warning" role="alert" className="mt-3">
          {conflict}
        </Alert>
      )}
      {open && !view.canDecide && !requester && (
        <p className="mt-2 text-xs text-slate-500">
          Waiting for {view.approverRoles.length > 0 ? rolesLabel(view.approverRoles) : 'an eligible admin'} to decide.
        </p>
      )}
      {open && !view.canDecide && requester && view.fourEyes && (
        <p className="mt-2 text-xs text-slate-500">
          You proposed it, so another admin has to decide it (four-eyes rule).
        </p>
      )}

      {open && (customerDecision || view.canDecide || canCancel) && (
        <div className="mt-3 flex flex-wrap gap-2">
          {customerDecision ? (
            <>
              <Button size="sm" onClick={() => void decide('approve')} disabled={busy}>
                {busyKind === 'approve' && <Spinner size="sm" className="[&_svg]:text-white" />}
                {confirmLabel}
              </Button>
              <Button
                size="sm"
                variant="secondary"
                onClick={() => void decide('reject', NOT_NOW_COMMENT)}
                disabled={busy}
              >
                {busyKind === 'reject' && <Spinner size="sm" />}
                Not now
              </Button>
            </>
          ) : view.canDecide ? (
            <>
              <Button size="sm" onClick={() => setDialog('approve')} disabled={busy}>
                Approve
              </Button>
              <Button size="sm" variant="secondary" onClick={() => setDialog('reject')} disabled={busy}>
                Reject
              </Button>
            </>
          ) : null}
          {canCancel && (
            <Button size="sm" variant="ghost" onClick={() => setDialog('cancel')} disabled={busy}>
              Cancel request
            </Button>
          )}
        </div>
      )}
      {busyKind === 'approve' && customerDecision && <p className="mt-2 text-xs text-slate-500">Running it now…</p>}

      <ApproveDialog
        open={dialog === 'approve'}
        title={view.summary.title}
        busy={busyKind === 'approve'}
        onClose={() => setDialog(null)}
        onSubmit={(comment) => decide('approve', comment)}
      />
      <ReasonDialog
        open={dialog === 'reject'}
        title="Reject this request?"
        description={`${view.summary.title}. Nothing will be done; the comment is shown to the requester.`}
        confirmLabel="Reject"
        cancelLabel="Keep it"
        maxLength={AI_LIMITS.comment}
        requiredMessage="Enter a comment: why is it not approved?"
        onClose={() => setDialog(null)}
        onSubmit={(comment) => decide('reject', comment)}
      />
      <ConfirmDialog
        open={dialog === 'cancel'}
        title="Cancel this request?"
        confirmLabel="Cancel request"
        cancelLabel="Keep it"
        tone="danger"
        pending={busyKind === 'cancel'}
        error={cancelError}
        onConfirm={() => void cancelRequest()}
        onClose={() => {
          setDialog(null)
          setCancelError(null)
        }}
      >
        <p>{view.summary.title}. Nothing will be done, and nobody can approve it any more.</p>
      </ConfirmDialog>
    </section>
  )
}

/**
 * A card in the chat or a list: shows the latest state of the request (kept current by the cache).
 * @param {{ approval: ApprovalView, compact?: boolean }} props
 */
export function ApprovalCard({ approval, compact }) {
  const query = useApproval(approval.id, approval)
  return <ApprovalDetails view={query.data ?? approval} compact={compact} />
}
