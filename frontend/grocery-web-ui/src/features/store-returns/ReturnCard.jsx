import { useId, useState } from 'react'
import { errorMessage, hasErrorCode } from '../../shared/api/problemDetail'
import { useToast } from '../../shared/components/toast/useToast'
import { ReasonDialog } from '../../shared/components/ReasonDialog'
import { Alert, Avatar, Badge, Button, Dialog, Spinner } from '../../shared/components/ui'
import { useNow } from '../../shared/hooks/useNow'
import { Icon } from '../../shared/icons'
import { cn } from '../../shared/utils/cn'
import { formatCount, formatDateTime, formatMoney } from '../../shared/utils/format'
import { isOpenReturn, RETURN_ERRORS, RETURN_LIMITS } from './api'
import { useReturnDecision } from './hooks'
import { deciderLabel, returnConflictMessage, returnReasonLabel, returnStatusLabel, returnStatusTone } from './labels'

/** @typedef {import('../../shared/components/ui').BadgeTone} BadgeTone */
/** @typedef {import('../../shared/icons').IconName} IconName */
/** @typedef {import('./api').ReturnDecider} ReturnDecider */
/** @typedef {import('./api').ReturnView} ReturnView */

/**
 * The card's colours follow its status badge: the bar on top and the picture tile.
 * @type {Record<BadgeTone, { bar: string, tile: string }>}
 */
const TONE_ACCENTS = {
  amber: { bar: 'from-amber-400 to-orange-500', tile: 'from-amber-50 to-orange-50 ring-amber-100' },
  red: { bar: 'from-rose-400 to-red-500', tile: 'from-rose-50 to-red-50 ring-rose-100' },
  blue: { bar: 'from-sky-400 to-blue-500', tile: 'from-sky-50 to-blue-50 ring-sky-100' },
  green: { bar: 'from-emerald-400 to-teal-500', tile: 'from-emerald-50 to-teal-50 ring-emerald-100' },
  violet: { bar: 'from-violet-400 to-fuchsia-500', tile: 'from-violet-50 to-fuchsia-50 ring-violet-100' },
  gray: { bar: 'from-slate-300 to-slate-400', tile: 'from-slate-50 to-slate-100 ring-slate-200' },
}

/**
 * The picture of a return in each status.
 * @param {string} status
 * @returns {IconName}
 */
function returnIcon(status) {
  if (status === 'ESCALATED') return 'returnAlert'
  if (status === 'APPROVED' || status === 'REFUNDED') return 'returnCheck'
  if (status === 'REJECTED') return 'returnCross'
  return 'return'
}

/** @param {{ status: string }} props */
export function ReturnStatusBadge({ status }) {
  return (
    <Badge tone={returnStatusTone(status)} dot>
      {returnStatusLabel(status)}
    </Badge>
  )
}

/**
 * @typedef {object} ApproveFormProps
 * @property {ReturnView} view
 * @property {boolean} askRestock Grocery orders: ask whether the goods came back sellable.
 * @property {(restock: boolean) => Promise<unknown>} onSubmit
 * @property {() => void} onClose
 */

/** @param {ApproveFormProps} props */
function ApproveForm({ view, askRestock, onSubmit, onClose }) {
  const name = useId()
  const [restock, setRestock] = useState('')
  const [error, setError] = useState(null)
  const [submitting, setSubmitting] = useState(false)

  async function submit(event) {
    event.preventDefault()
    if (askRestock && restock === '') {
      setError('Say whether the goods came back in a sellable state.')
      return
    }
    setError(null)
    setSubmitting(true)
    try {
      await onSubmit(askRestock && restock === 'yes')
    } catch (failure) {
      setError(errorMessage(failure))
    } finally {
      setSubmitting(false)
    }
  }

  return (
    <form onSubmit={(event) => void submit(event)} noValidate className="space-y-4">
      <p className="text-sm text-slate-600">
        The customer is refunded <strong className="text-slate-900">{formatMoney(view.refundAmount)}</strong> for{' '}
        {formatCount(view.items.reduce((sum, item) => sum + item.quantity, 0))} item(s).
      </p>
      {askRestock && (
        <fieldset className="space-y-2">
          <legend className="text-sm font-medium text-slate-700">Did the goods come back sellable?</legend>
          <label className="flex items-start gap-2 text-sm text-slate-700">
            <input
              type="radio"
              name={name}
              value="yes"
              checked={restock === 'yes'}
              onChange={() => setRestock('yes')}
              className="mt-0.5 accent-emerald-600"
            />
            <span>Yes — add them back to stock</span>
          </label>
          <label className="flex items-start gap-2 text-sm text-slate-700">
            <input
              type="radio"
              name={name}
              value="no"
              checked={restock === 'no'}
              onChange={() => setRestock('no')}
              className="mt-0.5 accent-emerald-600"
            />
            <span>No — do not restock (damaged, expired or not returned)</span>
          </label>
        </fieldset>
      )}
      {error && (
        <Alert tone="danger" role="alert">
          {error}
        </Alert>
      )}
      <div className="flex flex-col-reverse gap-2 sm:flex-row sm:justify-end">
        <Button variant="secondary" onClick={onClose} disabled={submitting}>
          Back
        </Button>
        <Button type="submit" disabled={submitting}>
          {submitting && <Spinner size="sm" className="[&_svg]:text-white" />}
          Approve and refund
        </Button>
      </div>
    </form>
  )
}

/**
 * @typedef {object} ReturnCardProps
 * @property {ReturnView} view
 * @property {ReturnDecider} decider
 * @property {boolean} canDecide The signed-in user may approve or reject (store owner, or an admin with the permission).
 * @property {'VENDOR' | 'RESTAURANT'} [storeKind] Restaurant owners never restock ("report a problem"); the option is hidden for restaurant orders anyway.
 * @property {boolean} [showStore] Admin view: show the store's name.
 */

/**
 * One return request: lines and refund, the customer's reason, the decision deadline, and approve / reject.
 * @param {ReturnCardProps} props
 */
export function ReturnCard({ view, decider, canDecide, storeKind, showStore = false }) {
  const titleId = useId()
  const toast = useToast()
  const decision = useReturnDecision(decider)
  const [dialog, setDialog] = useState(null)
  const [conflict, setConflict] = useState(null)
  const open = isOpenReturn(view.status) && (decider === 'admin' || view.status === 'REQUESTED')
  const now = useNow(60_000, open)
  const overdue = open && view.decideBy !== null && Date.parse(view.decideBy) <= now
  const askRestock = storeKind !== 'RESTAURANT' && view.orderType === 'GROCERY'
  const decided = deciderLabel(view.decidedByRole)

  /** Runs a decision; a 409 closes the dialog and explains, anything else stays in the dialog. */
  async function decide(run, done) {
    setConflict(null)
    try {
      await run()
      toast.success(done, { description: `${view.returnNumber} · order ${view.orderNumber}` })
      setDialog(null)
    } catch (error) {
      if (hasErrorCode(error, RETURN_ERRORS.notPending, RETURN_ERRORS.notFound)) {
        setDialog(null)
        setConflict(returnConflictMessage(view.returnNumber, error))
        return
      }
      throw error
    }
  }

  const accent = TONE_ACCENTS[returnStatusTone(view.status)]
  // Escalated, or past the decision time: the deadline line turns red.
  const urgent = view.status === 'ESCALATED' || overdue
  const restaurantOrder = view.orderType === 'RESTAURANT'

  return (
    <section
      aria-labelledby={titleId}
      className={cn(
        'group relative flex h-full flex-col overflow-hidden rounded-2xl bg-white p-5 shadow-sm ring-1 transition-all duration-300',
        'hover:-translate-y-0.5 hover:shadow-lg hover:shadow-slate-900/[0.08] motion-reduce:transition-none motion-reduce:hover:translate-y-0',
        open && view.status === 'ESCALATED' ? 'ring-rose-200' : 'ring-slate-200/80',
      )}
    >
      <span aria-hidden="true" className={cn('absolute inset-x-0 top-0 h-1 bg-gradient-to-r', accent.bar)} />

      {/* Header: picture, return number, order, status. */}
      <div className="flex items-start gap-3">
        <span
          aria-hidden="true"
          className={cn(
            'flex size-11 shrink-0 items-center justify-center rounded-xl bg-gradient-to-br ring-1 transition-transform duration-300 group-hover:scale-105 group-hover:-rotate-3 motion-reduce:transition-none',
            accent.tile,
          )}
        >
          <Icon name={returnIcon(view.status)} className="size-6" />
        </span>
        <div className="min-w-0 flex-1">
          <div className="flex flex-wrap items-start justify-between gap-2">
            <h3 id={titleId} className="font-mono text-sm font-semibold break-all text-slate-900">
              Return {view.returnNumber}
            </h3>
            <ReturnStatusBadge status={view.status} />
          </div>
          <p className="mt-0.5 text-xs text-slate-500">
            Order <span className="font-mono">{view.orderNumber}</span>
          </p>
        </div>
      </div>

      {/* Admins see whose return it is. */}
      {showStore && view.storeName && (
        <p className="mt-3 flex min-w-0 items-center gap-2 text-sm font-semibold text-slate-800">
          <Avatar name={view.storeName} size="sm" />
          <span className="truncate">{view.storeName}</span>
        </p>
      )}

      {/* Facts as chips: the kind of order and when it was asked for. */}
      <div className="mt-3 flex flex-wrap items-center gap-2 text-xs">
        <span className="inline-flex items-center gap-1.5 rounded-full bg-slate-50 px-2.5 py-1 text-slate-600 ring-1 ring-slate-200 ring-inset">
          <Icon name={restaurantOrder ? 'cloche' : 'basket'} className="size-3.5" />
          {restaurantOrder ? 'Restaurant' : 'Grocery'}
        </span>
        <span className="inline-flex items-center gap-1.5 rounded-full bg-slate-50 px-2.5 py-1 text-slate-600 ring-1 ring-slate-200 ring-inset">
          <Icon name="calendar" className="size-3.5" />
          Requested <time dateTime={view.requestedAt}>{formatDateTime(view.requestedAt)}</time>
        </span>
      </div>

      {/* The customer's reason and note. */}
      <p className="mt-4 flex items-start gap-2 rounded-xl bg-gradient-to-br from-slate-50 to-white px-3.5 py-2.5 text-sm text-slate-700 ring-1 ring-slate-100">
        <Icon name="chat" className="mt-0.5 size-4" />
        <span className="min-w-0">
          <span className="font-semibold text-slate-900">{returnReasonLabel(view.reason)}</span>
          {view.note && <span className="break-words"> — “{view.note}”</span>}
        </span>
      </p>

      <dl className="mt-3 text-sm">
        {view.items.map((item) => (
          <div
            key={item.itemId}
            className="flex items-start justify-between gap-3 border-b border-dashed border-slate-200 px-1 py-1.5"
          >
            <dt className="min-w-0 break-words text-slate-600">
              {item.name} × {formatCount(item.quantity)}
            </dt>
            <dd className="shrink-0 text-slate-900 tabular-nums">{formatMoney(item.refundAmount)}</dd>
          </div>
        ))}
        <div className="mt-2 flex items-center justify-between gap-3 rounded-xl bg-gradient-to-r from-emerald-50 to-teal-50 px-3 py-2 ring-1 ring-emerald-100">
          <dt className="flex items-center gap-1.5 font-semibold text-emerald-900">
            <Icon name="savings" className="size-4" />
            Refund
          </dt>
          <dd className="font-bold text-emerald-800 tabular-nums">{formatMoney(view.refundAmount)}</dd>
        </div>
      </dl>

      {open && view.decideBy && (
        <p
          className={cn(
            'mt-3 flex items-start gap-1.5 rounded-xl px-3 py-2 text-xs ring-1 ring-inset',
            urgent
              ? 'bg-rose-50 font-medium text-rose-700 ring-rose-200'
              : 'bg-amber-50/70 text-amber-800 ring-amber-200',
          )}
        >
          <Icon name={urgent ? 'warning' : 'hourglass'} className="mt-px size-3.5" />
          <span className="min-w-0">
            {view.status === 'ESCALATED'
              ? 'The store did not decide in time — an admin decides now.'
              : overdue
                ? 'The decision time is over — it is being escalated to admins.'
                : `Decide by ${formatDateTime(view.decideBy)}, or it escalates to admins.`}
          </span>
        </p>
      )}
      {!open && (view.decidedAt || view.comment || view.restock !== null) && (
        <div className="mt-3 flex items-start gap-2 rounded-xl bg-slate-50 px-3 py-2.5 text-xs text-slate-600 ring-1 ring-slate-100">
          <Icon name="clipboard" className="mt-px size-4" />
          <div className="min-w-0 space-y-0.5">
            {view.decidedAt && (
              <p>
                Decided {formatDateTime(view.decidedAt)}
                {decided ? ` by ${decided}` : ''}.
              </p>
            )}
            {view.restock !== null && view.orderType === 'GROCERY' && (
              <p>{view.restock ? 'Restocked.' : 'Not restocked.'}</p>
            )}
            {view.comment && <p className="break-words">“{view.comment}”</p>}
            {view.refundedAt && <p>Refunded {formatDateTime(view.refundedAt)}.</p>}
          </div>
        </div>
      )}

      {conflict && (
        <Alert tone="warning" role="alert" className="mt-3">
          {conflict}
        </Alert>
      )}

      {/* mt-auto keeps the buttons at the bottom when cards in a row have different heights. */}
      {open && canDecide && (
        <div className="mt-auto flex flex-wrap gap-2 pt-4">
          <Button size="sm" onClick={() => setDialog('approve')} disabled={decision.isPending}>
            <Icon name="check" className="size-4" />
            Approve
          </Button>
          <Button size="sm" variant="secondary" onClick={() => setDialog('reject')} disabled={decision.isPending}>
            <Icon name="close" className="size-4" />
            Reject
          </Button>
        </div>
      )}

      <Dialog
        open={dialog === 'approve'}
        onClose={() => setDialog(null)}
        title={`Approve return ${view.returnNumber}?`}
        size="sm"
        dismissible={!decision.isPending}
      >
        <ApproveForm
          view={view}
          askRestock={askRestock}
          onClose={() => setDialog(null)}
          onSubmit={(restock) =>
            decide(() => decision.mutateAsync({ kind: 'approve', id: view.id, restock }), 'Return approved')
          }
        />
      </Dialog>
      <ReasonDialog
        open={dialog === 'reject'}
        title={`Reject return ${view.returnNumber}?`}
        description="Nothing is refunded. Your comment is shown to the customer."
        confirmLabel="Reject return"
        cancelLabel="Back"
        maxLength={RETURN_LIMITS.comment}
        requiredMessage="Enter a comment for the customer: why is the return rejected?"
        onClose={() => setDialog(null)}
        onSubmit={(comment) =>
          decide(() => decision.mutateAsync({ kind: 'reject', id: view.id, comment }), 'Return rejected')
        }
      />
    </section>
  )
}
