import { useId } from 'react'
import { Link } from 'react-router'
import { MY_RETURNS_PAGE_SIZE } from '../../../features/returns/api'
import { useMyReturns } from '../../../features/returns/hooks'
import {
  returnReasonLabel,
  returnStatusLabel,
  returnStatusText,
  returnStatusTone,
} from '../../../features/returns/labels'
import { ApiError } from '../../../shared/api/problemDetail'
import { Badge, EmptyState, ErrorState, Pagination, Skeleton } from '../../../shared/components/ui'
import { ExportButton } from '../../../shared/export'
import { pageFromParam, pageToParam, useSearchParamsUpdater } from '../../../shared/hooks/useSearchParamsUpdater'
import { Icon } from '../../../shared/icons'
import { OrderSuccessIllustration } from '../../../shared/illustrations'
import { cn } from '../../../shared/utils/cn'
import { formatDateTime, formatPrice } from '../../../shared/utils/format'
import { ShopperPageHeader } from '../components/ShopperPageHeader'

/** @typedef {import('../../../features/returns/api').ReturnView} ReturnView */
/** @typedef {import('../../../shared/icons').IconName} IconName */

/** @type {Record<string, IconName>} */
const STATUS_ICONS = {
  REQUESTED: 'clock',
  ESCALATED: 'shield',
  APPROVED: 'checkCircle',
  REFUNDED: 'return',
  REJECTED: 'xCircle',
}

/**
 * How a return goes, in three steps. The time limits are platform settings (48 h and 24 h by default), so the copy names none.
 * @type {ReadonlyArray<{ icon: IconName, title: string, text: string }>}
 */
const HOW_IT_WORKS = [
  {
    icon: 'box',
    title: 'Ask soon after delivery',
    text: 'Open a delivered order and choose “Return items” (or “Report a problem” for food) while the order offers it.',
  },
  {
    icon: 'store',
    title: 'The store decides',
    text: 'If it does not decide in time, our support team decides instead.',
  },
  { icon: 'card', title: 'Refund to your payment', text: 'Approved refunds go back to the way you paid.' },
]

/** @typedef {'done' | 'current' | 'upcoming' | 'failed'} StepState */

/**
 * Request sent → Decision → Refund, from the return's status (labels differ from the status badge on purpose).
 * @param {string} status
 * @returns {Array<{ label: string, state: StepState }>}
 */
function returnSteps(status) {
  const decision =
    status === 'REJECTED' ? 'failed' : status === 'APPROVED' || status === 'REFUNDED' ? 'done' : 'current'
  const refund = status === 'REFUNDED' ? 'done' : status === 'APPROVED' ? 'current' : 'upcoming'
  return [
    { label: 'Request sent', state: 'done' },
    {
      label: status === 'ESCALATED' ? 'Support decides' : status === 'REJECTED' ? 'Declined' : 'Decision',
      state: decision,
    },
    { label: 'Refund', state: status === 'REJECTED' ? 'upcoming' : refund },
  ]
}

/** @type {Record<StepState, string>} */
const STEP_DOT = {
  done: 'bg-emerald-500 ring-emerald-100',
  current: 'bg-amber-400 ring-amber-100 animate-pulse motion-reduce:animate-none',
  upcoming: 'bg-slate-200 ring-slate-100',
  failed: 'bg-rose-500 ring-rose-100',
}

/** @param {{ status: string }} props */
function ReturnProgress({ status }) {
  const steps = returnSteps(status)
  return (
    <ol aria-hidden="true" className="mt-4 grid grid-cols-3 gap-2">
      {steps.map((step, index) => (
        <li key={step.label} className="min-w-0">
          <div className="flex items-center gap-2">
            <span className={cn('size-2.5 shrink-0 rounded-full ring-4', STEP_DOT[step.state])} />
            {index < steps.length - 1 && (
              <span
                className={cn('h-0.5 flex-1 rounded-full', step.state === 'done' ? 'bg-emerald-300' : 'bg-slate-200')}
              />
            )}
          </div>
          <p
            className={cn(
              'mt-1.5 truncate text-xs font-medium',
              step.state === 'upcoming'
                ? 'text-slate-400'
                : step.state === 'failed'
                  ? 'text-rose-700'
                  : 'text-slate-700',
            )}
          >
            {step.label}
          </p>
        </li>
      ))}
    </ol>
  )
}

/** @param {{ view: ReturnView }} props */
function ReturnCard({ view }) {
  const titleId = useId()
  const restaurant = view.orderType === 'RESTAURANT'
  return (
    <li>
      <article
        aria-labelledby={titleId}
        className="rounded-2xl border border-slate-200/80 bg-white p-4 shadow-sm transition-shadow hover:shadow-md sm:p-5"
      >
        <div className="flex items-start gap-3.5">
          <span
            aria-hidden="true"
            className={cn(
              'flex size-12 shrink-0 items-center justify-center rounded-2xl ring-1 ring-inset',
              restaurant ? 'bg-amber-50 ring-amber-100' : 'bg-violet-50 ring-violet-100',
            )}
          >
            <Icon name={restaurant ? 'chat' : 'return'} className="size-6" />
          </span>
          <div className="min-w-0 flex-1">
            <div className="flex flex-wrap items-start justify-between gap-2">
              <div className="min-w-0">
                <h2 id={titleId} className="text-base font-semibold text-slate-900">
                  Return <span className="font-mono">{view.returnNumber}</span>
                </h2>
                <p className="mt-0.5 text-xs text-slate-500">
                  {view.storeName} · order{' '}
                  <Link
                    to={`/orders/${encodeURIComponent(view.orderId)}`}
                    className="font-mono font-medium text-emerald-700 hover:underline"
                  >
                    {view.orderNumber}
                  </Link>{' '}
                  · requested {formatDateTime(view.requestedAt)}
                </p>
              </div>
              <Badge tone={returnStatusTone(view.status)} className="gap-1 px-2.5 py-1 font-semibold">
                <Icon name={STATUS_ICONS[view.status] ?? 'info'} className="size-3.5" />
                {returnStatusLabel(view.status)}
              </Badge>
            </div>
            <ReturnProgress status={view.status} />
          </div>
        </div>

        <div className="mt-4 grid gap-4 sm:grid-cols-[minmax(0,1fr)_11rem]">
          <ul
            aria-label={`Items in return ${view.returnNumber}`}
            className="divide-y divide-slate-100 rounded-xl bg-slate-50/80 px-3.5 py-1 text-sm ring-1 ring-slate-100"
          >
            {view.items.map((line) => (
              <li key={line.itemId} className="flex justify-between gap-4 py-2">
                <span className="min-w-0 text-slate-700">
                  <span className="font-semibold text-slate-900 tabular-nums">{line.quantity} ×</span> {line.name}
                </span>
                <span className="text-slate-500 tabular-nums">{formatPrice(line.refundAmount)}</span>
              </li>
            ))}
          </ul>
          <div className="flex flex-col justify-center rounded-xl bg-gradient-to-br from-emerald-50 to-teal-50 px-4 py-3 ring-1 ring-emerald-100">
            <span className="text-xs font-medium text-emerald-800">Refund</span>
            <span className="text-2xl font-bold tracking-tight text-slate-900 tabular-nums">
              {formatPrice(view.refundAmount)}
            </span>
          </div>
        </div>

        <p className="mt-3 text-xs text-slate-500">
          Reason: {returnReasonLabel(view.reason)}
          {view.note ? ` — “${view.note}”` : ''}
        </p>
        <p className="mt-1.5 flex items-start gap-2 text-sm text-slate-700">
          <Icon name="info" className="mt-0.5 size-4 shrink-0" />
          <span>{returnStatusText(view)}</span>
        </p>
      </article>
    </li>
  )
}

function HowReturnsWork() {
  return (
    <section
      aria-labelledby="how-returns-work"
      className="animate-fade-up mb-6 rounded-2xl border border-slate-200/80 bg-white p-4 shadow-xs sm:p-5"
    >
      <h2 id="how-returns-work" className="text-sm font-semibold text-slate-900">
        How returns work
      </h2>
      <ol className="mt-3 grid gap-3 sm:grid-cols-3">
        {HOW_IT_WORKS.map((step, index) => (
          <li key={step.title} className="flex items-start gap-3 rounded-xl bg-slate-50/80 p-3 ring-1 ring-slate-100">
            <span
              aria-hidden="true"
              className="relative flex size-10 shrink-0 items-center justify-center rounded-xl bg-white shadow-xs ring-1 ring-slate-200"
            >
              <Icon name={step.icon} className="size-5" />
              <span className="absolute -top-1.5 -left-1.5 flex size-5 items-center justify-center rounded-full bg-emerald-600 text-[0.65rem] font-bold text-white">
                {index + 1}
              </span>
            </span>
            <span className="min-w-0">
              <span className="block text-sm font-semibold text-slate-900">{step.title}</span>
              <span className="block text-xs leading-relaxed text-slate-500">{step.text}</span>
            </span>
          </li>
        ))}
      </ol>
    </section>
  )
}

/** `/account/returns` — the customer's return requests (newest first) with their status and refund. */
export function MyReturnsPage() {
  const [searchParams, updateParams] = useSearchParamsUpdater()
  const page = pageFromParam(searchParams.get('page'))
  const returns = useMyReturns({ page, size: MY_RETURNS_PAGE_SIZE })

  let content
  if (returns.isPending) {
    content = (
      <div role="status" aria-label="Loading your returns" className="space-y-3">
        <Skeleton className="h-52 w-full rounded-2xl" />
        <Skeleton className="h-52 w-full rounded-2xl" />
      </div>
    )
  } else if (returns.isError) {
    content = (
      <ErrorState
        title="Could not load your returns"
        message={returns.error.message}
        correlationId={returns.error instanceof ApiError ? returns.error.correlationId : null}
        onRetry={() => void returns.refetch()}
      />
    )
  } else if (returns.data.content.length === 0) {
    content = (
      <EmptyState
        size="lg"
        illustration={<OrderSuccessIllustration className="animate-float" />}
        title="No returns yet"
        description="If something in a delivered order is wrong, open the order soon after delivery and choose “Return items”."
        action={
          <Link
            to="/orders?status=DELIVERED"
            className="inline-flex h-10 items-center justify-center gap-2 rounded-xl bg-white px-4 text-sm font-semibold text-slate-700 shadow-xs ring-1 ring-slate-300 ring-inset transition-colors hover:bg-slate-50 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-emerald-600"
          >
            See delivered orders
          </Link>
        }
      />
    )
  } else {
    content = (
      <>
        <ul
          aria-label="Your returns"
          className={cn('animate-fade-up space-y-4 transition-opacity', returns.isPlaceholderData && 'opacity-60')}
        >
          {returns.data.content.map((view) => (
            <ReturnCard key={view.id} view={view} />
          ))}
        </ul>
        <Pagination
          page={returns.data.page}
          size={returns.data.size}
          totalElements={returns.data.totalElements}
          totalPages={returns.data.totalPages}
          disabled={returns.isFetching}
          onPageChange={(next) => updateParams({ page: pageToParam(next) })}
        />
      </>
    )
  }

  return (
    <div className="w-full">
      <ShopperPageHeader
        icon="return"
        eyebrow="Orders"
        title="My returns"
        description="Items you sent back or problems you reported, and their refunds."
        actions={<ExportButton path="/returns/export" label="your returns" total={returns.data?.totalElements} />}
      />
      <HowReturnsWork />
      {content}
    </div>
  )
}
