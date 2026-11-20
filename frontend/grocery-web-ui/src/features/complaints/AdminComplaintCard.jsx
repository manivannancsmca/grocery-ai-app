import { useId, useState } from 'react'
import { Link } from 'react-router'
import { ApiError, errorMessage } from '../../shared/api/problemDetail'
import { useToast } from '../../shared/components/toast/useToast'
import { Alert, Button, Dialog, Spinner, TextareaField } from '../../shared/components/ui'
import { Icon } from '../../shared/icons'
import { formatDateTime } from '../../shared/utils/format'
import { complaintCategoryLabel, complaintConflictMessage, COMPLAINT_LIMITS, isComplaintConflict, shortId } from './api'
import { CategoryTile, ComplaintStatusBadge, StatusBar } from './ComplaintBits'
import { useResolveComplaint, useStartComplaint } from './hooks'

/** @typedef {import('./api').AdminComplaintView} AdminComplaintView */

const CHIP =
  'inline-flex min-w-0 items-center gap-1.5 rounded-full bg-slate-50 px-2.5 py-1 text-slate-600 ring-1 ring-slate-200 ring-inset'

/**
 * @typedef {object} ResolveFormProps
 * @property {AdminComplaintView} complaint
 * @property {(resolution: string) => Promise<unknown>} onSubmit Sends the answer; rejects to show the error in the dialog.
 * @property {() => void} onClose
 */

/**
 * The answer for the customer (5–1000 characters), with the customer's words above it for context.
 * @param {ResolveFormProps} props
 */
function ResolveForm({ complaint, onSubmit, onClose }) {
  const [resolution, setResolution] = useState('')
  const [fieldError, setFieldError] = useState()
  const [error, setError] = useState(null)
  const [submitting, setSubmitting] = useState(false)

  async function submit(event) {
    event.preventDefault()
    if (resolution.trim().length < COMPLAINT_LIMITS.resolutionMin) {
      setFieldError(`Write an answer of at least ${COMPLAINT_LIMITS.resolutionMin} characters.`)
      return
    }
    setFieldError(undefined)
    setError(null)
    setSubmitting(true)
    try {
      await onSubmit(resolution.trim())
    } catch (failure) {
      // A 400 on the answer goes under the field; anything else above the buttons.
      const onField =
        failure instanceof ApiError ? failure.fieldErrors.find((item) => item.field === 'resolution') : undefined
      if (onField) setFieldError(onField.message)
      else setError(errorMessage(failure))
    } finally {
      setSubmitting(false)
    }
  }

  return (
    <form onSubmit={(event) => void submit(event)} noValidate className="space-y-4">
      <blockquote className="line-clamp-4 rounded-xl bg-slate-50 px-3.5 py-2.5 text-sm break-words whitespace-pre-line text-slate-700 ring-1 ring-slate-100">
        {complaint.description}
      </blockquote>
      <TextareaField
        label="Answer for the customer"
        required
        rows={5}
        maxLength={COMPLAINT_LIMITS.resolutionMax}
        value={resolution}
        error={fieldError}
        onChange={(event) => {
          setResolution(event.target.value)
          setFieldError(undefined)
        }}
        hint={
          <span className="flex justify-between gap-3">
            <span>The customer reads it on their Help & support page.</span>
            <span className="shrink-0 tabular-nums">
              {resolution.length} / {COMPLAINT_LIMITS.resolutionMax}
            </span>
          </span>
        }
      />
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
          Resolve complaint
        </Button>
      </div>
    </form>
  )
}

/**
 * @typedef {object} AdminComplaintCardProps
 * @property {AdminComplaintView} complaint
 * @property {boolean} canHandle The signed-in admin may start and resolve complaints (SUPER_ADMIN, OPS_ADMIN, SUPPORT).
 * @property {(message: string) => void} onConflict Someone else changed the complaint first (409 / 404): the page shows the message above the list, because the refreshed list may no longer contain this card.
 */

/**
 * One complaint in the admin queue: who, what, which order and store, the answer once resolved, and Start / Resolve.
 * @param {AdminComplaintCardProps} props
 */
export function AdminComplaintCard({ complaint, canHandle, onConflict }) {
  const titleId = useId()
  const toast = useToast()
  const start = useStartComplaint()
  const resolve = useResolveComplaint()
  const [resolving, setResolving] = useState(false)
  const [error, setError] = useState(null)
  const number = complaint.complaintNumber
  const resolved = complaint.status === 'RESOLVED'
  const busy = start.isPending || resolve.isPending

  async function startNow() {
    setError(null)
    try {
      await start.mutateAsync(complaint.id)
      toast.success(`${number} started`, { description: 'It is now in progress.' })
    } catch (failure) {
      if (isComplaintConflict(failure)) onConflict(complaintConflictMessage(number, failure))
      else setError(errorMessage(failure))
    }
  }

  /** Used by the dialog: a conflict closes it and explains on the page; other errors stay in the dialog. */
  async function resolveNow(resolution) {
    try {
      await resolve.mutateAsync({ id: complaint.id, resolution })
      setResolving(false)
      toast.success(`${number} resolved`, { description: 'The customer can read your answer now.' })
    } catch (failure) {
      if (!isComplaintConflict(failure)) throw failure
      setResolving(false)
      onConflict(complaintConflictMessage(number, failure))
    }
  }

  return (
    <section
      aria-labelledby={titleId}
      className="relative flex h-full flex-col overflow-hidden rounded-2xl bg-white p-4 pt-5 shadow-sm ring-1 ring-slate-200/80 transition-shadow hover:shadow-md sm:p-5"
    >
      <StatusBar status={complaint.status} />

      {/* Header: picture, number, category and time, status. */}
      <div className="flex items-start gap-3">
        <CategoryTile category={complaint.category} />
        <div className="min-w-0 flex-1">
          <div className="flex flex-wrap items-start justify-between gap-x-3 gap-y-1.5">
            <h3 id={titleId} className="font-mono text-sm font-semibold break-all text-slate-900">
              Complaint {number}
            </h3>
            <ComplaintStatusBadge status={complaint.status} />
          </div>
          <p className="mt-0.5 text-xs text-slate-500">
            <span className="font-semibold text-slate-700">{complaintCategoryLabel(complaint.category)}</span> · raised{' '}
            <time dateTime={complaint.createdAt}>{formatDateTime(complaint.createdAt)}</time>
          </p>
        </div>
      </div>

      {/* Facts as chips: the customer, the order (opens the order drawer) and its store. */}
      <div className="mt-3 flex flex-wrap items-center gap-2 text-xs">
        <span className={CHIP} title={`Customer ${complaint.customerId}`}>
          <Icon name="user" className="size-3.5" />
          {/* The space keeps "Customer 0193c0de…" two words for screen readers; the chip's gap spaces it on screen. */}
          {'Customer '}
          <span className="truncate font-mono">{shortId(complaint.customerId)}</span>
        </span>
        {complaint.orderId ? (
          <Link
            to={`/admin/orders?order=${encodeURIComponent(complaint.orderId)}`}
            className="inline-flex min-h-10 items-center gap-1.5 rounded-xl bg-white px-3 text-sm font-medium text-emerald-800 shadow-xs ring-1 ring-emerald-200 ring-inset transition-colors hover:bg-emerald-50 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-emerald-600"
          >
            <Icon name="receipt" className="size-3.5" />
            Order <span className="font-mono">{complaint.orderNumber}</span>
          </Link>
        ) : (
          <span className={CHIP}>
            <Icon name="info" className="size-3.5" />
            Not about an order
          </span>
        )}
        {complaint.storeName && (
          <span className={CHIP}>
            <Icon name="store" className="size-3.5" />
            <span className="truncate">{complaint.storeName}</span>
          </span>
        )}
      </div>

      {/* The customer's words. */}
      <p className="mt-3 flex items-start gap-2 rounded-xl bg-gradient-to-br from-slate-50 to-white px-3.5 py-2.5 text-sm text-slate-700 ring-1 ring-slate-100">
        <Icon name="chat" className="mt-0.5 size-4" />
        <span className="min-w-0 break-words whitespace-pre-line">{complaint.description}</span>
      </p>

      {resolved && (
        <div className="mt-3 rounded-xl bg-gradient-to-br from-emerald-50 to-teal-50 px-3.5 py-2.5 ring-1 ring-emerald-200">
          <p className="text-xs font-semibold text-emerald-900">Answer sent to the customer</p>
          <p className="mt-1 text-sm break-words whitespace-pre-line text-slate-800">{complaint.resolution}</p>
          <p className="mt-1.5 text-xs text-emerald-800/80">
            Resolved {complaint.resolvedAt ? formatDateTime(complaint.resolvedAt) : ''}
            {complaint.handledBy ? ` by admin ${shortId(complaint.handledBy)}` : ''}
          </p>
        </div>
      )}
      {complaint.status === 'IN_PROGRESS' && complaint.handledBy && (
        <p className="mt-3 flex items-start gap-1.5 text-xs text-slate-500">
          <Icon name="hourglass" className="mt-px size-3.5" />
          <span className="min-w-0">
            Started by admin <span className="font-mono">{shortId(complaint.handledBy)}</span> · updated{' '}
            {formatDateTime(complaint.updatedAt)}
          </span>
        </p>
      )}

      {error && (
        <Alert tone="danger" role="alert" className="mt-3">
          {error}
        </Alert>
      )}

      {/* mt-auto keeps the buttons at the bottom when cards in a row have different heights. */}
      {canHandle && !resolved && (
        <div className="mt-auto flex flex-wrap gap-2 pt-4">
          {complaint.status === 'OPEN' && (
            <Button variant="secondary" onClick={() => void startNow()} disabled={busy}>
              {start.isPending ? <Spinner size="sm" /> : <Icon name="hourglass" className="size-4" />}
              Start
            </Button>
          )}
          <Button onClick={() => setResolving(true)} disabled={busy}>
            <Icon name="check" className="size-4" />
            Resolve
          </Button>
        </div>
      )}

      <Dialog
        open={resolving}
        onClose={() => setResolving(false)}
        title={`Resolve ${number}?`}
        description="Write the answer the customer sees. A resolved complaint cannot be reopened."
        size="sm"
        dismissible={!resolve.isPending}
      >
        <ResolveForm complaint={complaint} onSubmit={resolveNow} onClose={() => setResolving(false)} />
      </Dialog>
    </section>
  )
}
