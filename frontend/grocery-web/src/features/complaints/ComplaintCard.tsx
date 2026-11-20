import { useId } from 'react'
import { Link } from 'react-router'
import { Icon } from '../../shared/icons'
import { cn } from '../../shared/utils/cn'
import { formatDateTime } from '../../shared/utils/format'
import { complaintCategoryLabel, type ComplaintView } from './api'
import { CategoryTile, ComplaintStatusBadge, StatusBar } from './ComplaintBits'

type StepState = 'done' | 'current' | 'upcoming'

/** Received → Working on it → Answered, from the complaint's status. */
function complaintSteps(status: string): Array<{ label: string; state: StepState }> {
  const resolved = status === 'RESOLVED'
  return [
    { label: 'Received', state: 'done' },
    { label: 'Working on it', state: resolved ? 'done' : status === 'IN_PROGRESS' ? 'current' : 'upcoming' },
    { label: 'Answered', state: resolved ? 'done' : 'upcoming' },
  ]
}

const STEP_BAR: Record<StepState, string> = {
  done: 'bg-emerald-500',
  current: 'bg-sky-400 animate-pulse motion-reduce:animate-none',
  upcoming: 'bg-slate-200',
}

/** Three slim bars with their names (decorative: the badge and the line under it say the same in words). */
function ComplaintProgress({ status }: { status: string }) {
  const steps = complaintSteps(status)
  return (
    <ol aria-hidden="true" className="grid grid-cols-3 gap-1.5">
      {steps.map((step) => (
        <li key={step.label} className="min-w-0">
          <span className={cn('block h-1.5 rounded-full', STEP_BAR[step.state])} />
          <span className={cn('mt-1 block truncate text-[11px] font-medium', step.state === 'upcoming' ? 'text-slate-400' : 'text-slate-600')}>
            {step.label}
          </span>
        </li>
      ))}
    </ol>
  )
}

/** One sentence on where an unanswered complaint stands. */
function waitingText(status: string): string {
  return status === 'IN_PROGRESS'
    ? 'Our support team is working on it. Their answer will appear here.'
    : 'We have your complaint. Our support team will pick it up soon.'
}

/**
 * One of the customer's complaints: category, number, status and progress, the order it is about, their words and —
 * once resolved — our answer in a highlighted panel. `highlighted` marks the one just sent.
 */
export function ComplaintCard({ complaint, highlighted = false }: { complaint: ComplaintView; highlighted?: boolean }) {
  const titleId = useId()
  const numberId = useId()
  const resolved = complaint.status === 'RESOLVED'
  return (
    <li className="min-w-0">
      <article
        aria-labelledby={`${titleId} ${numberId}`}
        className={cn(
          'relative overflow-hidden rounded-2xl bg-white p-4 pt-5 shadow-sm ring-1 transition-shadow hover:shadow-md sm:p-5',
          highlighted ? 'ring-2 ring-emerald-400' : 'ring-slate-200/80',
        )}
      >
        <StatusBar status={complaint.status} />

        {/* Header: picture, category, number and date, status. */}
        <div className="flex items-start gap-3">
          <CategoryTile category={complaint.category} />
          <div className="min-w-0 flex-1">
            <div className="flex flex-wrap items-start justify-between gap-x-3 gap-y-1.5">
              <div className="min-w-0">
                <h3 id={titleId} className="flex flex-wrap items-center gap-2 text-base font-semibold text-slate-900">
                  {complaintCategoryLabel(complaint.category)}
                  {highlighted && <span className="rounded-full bg-emerald-600 px-2 py-0.5 text-[11px] font-bold text-white">New</span>}
                </h3>
                <p className="mt-0.5 text-xs text-slate-500">
                  <span id={numberId} className="font-mono font-medium text-slate-700">
                    {complaint.complaintNumber}
                  </span>{' '}
                  · raised <time dateTime={complaint.createdAt}>{formatDateTime(complaint.createdAt)}</time>
                </p>
              </div>
              <ComplaintStatusBadge status={complaint.status} />
            </div>
          </div>
        </div>

        {/* The order it is about, with its store. */}
        {complaint.orderId && (
          <p className="mt-3 flex min-w-0 flex-wrap items-center gap-x-2 gap-y-1 text-sm">
            <Link
              to={`/orders/${encodeURIComponent(complaint.orderId)}`}
              className="inline-flex min-h-10 items-center gap-1.5 rounded-xl bg-slate-50 px-3 text-slate-700 ring-1 ring-slate-200 ring-inset transition-colors hover:bg-emerald-50 hover:text-emerald-800 hover:ring-emerald-200 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-emerald-600"
            >
              <Icon name="receipt" className="size-4" />
              Order <span className="font-mono font-medium">{complaint.orderNumber}</span>
            </Link>
            {complaint.storeName && <span className="min-w-0 truncate text-slate-500">{complaint.storeName}</span>}
          </p>
        )}

        {/* The customer's own words. */}
        <p className="mt-3 rounded-xl bg-slate-50/80 px-3.5 py-2.5 text-sm leading-relaxed break-words whitespace-pre-line text-slate-700 ring-1 ring-slate-100">
          {complaint.description}
        </p>

        {resolved ? (
          <div className="mt-3 rounded-xl bg-gradient-to-br from-emerald-50 to-teal-50 px-3.5 py-3 ring-1 ring-emerald-200">
            <p className="flex items-center gap-2 text-sm font-semibold text-emerald-900">
              <Icon name="chat" className="size-4" />
              Our answer
            </p>
            <p className="mt-1 text-sm leading-relaxed break-words whitespace-pre-line text-slate-800">{complaint.resolution}</p>
            {complaint.resolvedAt && (
              <p className="mt-1.5 text-xs text-emerald-800/80">
                Answered <time dateTime={complaint.resolvedAt}>{formatDateTime(complaint.resolvedAt)}</time>
              </p>
            )}
          </div>
        ) : (
          <div className="mt-4 space-y-2">
            <ComplaintProgress status={complaint.status} />
            <p className="flex items-start gap-2 text-sm text-slate-600">
              <Icon name={complaint.status === 'IN_PROGRESS' ? 'hourglass' : 'clock'} className="mt-0.5 size-4" />
              <span className="min-w-0">{waitingText(complaint.status)}</span>
            </p>
          </div>
        )}
      </article>
    </li>
  )
}
