import { type ReactNode, useId, useState } from 'react'
import { ApiError } from '../../../shared/api/problemDetail'
import { DEFAULT_PAGE_SIZE } from '../../../shared/api/types'
import { ErrorState, Pagination, Skeleton } from '../../../shared/components/ui'
import { useNow } from '../../../shared/hooks/useNow'
import { Icon } from '../../../shared/icons'
import { cn } from '../../../shared/utils/cn'
import { formatDateTime } from '../../../shared/utils/format'
import type { StakeholderLifecycle } from '../types'
import { initialsOf } from '../../../shared/utils/initials'
import { ACCENTS, type ApprovalAccent, waitingSince } from './approvalLook'
import { useStakeholderList } from './hooks'
import type { StakeholderKind } from './kind'
import { StakeholderDetailDrawer } from './StakeholderDetailDrawer'

interface SubmissionCardProps {
  name: string
  summary: ReactNode
  submittedAt: string | null
  now: number
  accent: ApprovalAccent
  onReview: () => void
}

/** One submission waiting for a decision: who, how to reach them, how long it has waited, and "Review". */
function SubmissionCard({ name, summary, submittedAt, now, accent, onReview }: SubmissionCardProps) {
  const titleId = useId()
  const look = ACCENTS[accent]
  const waiting = waitingSince(submittedAt, now)
  return (
    <article
      aria-labelledby={titleId}
      className="group relative flex h-full flex-col overflow-hidden rounded-2xl bg-white p-5 shadow-sm ring-1 ring-slate-200/80 transition-all duration-300 hover:-translate-y-1 hover:shadow-xl hover:shadow-slate-900/10 hover:ring-slate-300 motion-reduce:transition-none motion-reduce:hover:translate-y-0"
    >
      <div aria-hidden="true" className={cn('absolute inset-x-0 top-0 h-1 bg-gradient-to-r', look.gradient)} />
      <div
        aria-hidden="true"
        className={cn('pointer-events-none absolute -top-14 -right-14 size-36 rounded-full opacity-0 blur-2xl transition-opacity duration-500 group-hover:opacity-100', look.glow)}
      />
      <div className="relative flex items-start gap-4">
        <span
          aria-hidden="true"
          className={cn('flex size-12 shrink-0 items-center justify-center rounded-2xl bg-gradient-to-br text-base font-bold text-white shadow-lg', look.avatar, look.shadow)}
        >
          {initialsOf(name)}
        </span>
        <div className="min-w-0 flex-1">
          <h3 id={titleId} className="truncate text-base font-semibold text-slate-900" title={name}>
            {name}
          </h3>
          <p className="mt-0.5 truncate text-sm text-slate-500">{summary}</p>
        </div>
      </div>

      <div className="relative mt-4 flex flex-wrap items-center gap-2 text-xs">
        <span className={cn('inline-flex items-center gap-1.5 rounded-full px-2.5 py-1 font-semibold ring-1 ring-inset', waiting.tone)}>
          <Icon name="hourglass" className="size-3.5" />
          {waiting.label}
        </span>
        {submittedAt && (
          <span className="inline-flex items-center gap-1.5 rounded-full bg-slate-50 px-2.5 py-1 text-slate-600 ring-1 ring-slate-200 ring-inset">
            <Icon name="calendar" className="size-3.5" />
            <time dateTime={submittedAt}>{formatDateTime(submittedAt)}</time>
          </span>
        )}
      </div>

      <div className="relative mt-auto flex items-center justify-between gap-3 pt-5">
        <span className="inline-flex items-center gap-1.5 text-xs font-semibold text-amber-700">
          <span aria-hidden="true" className="size-2 animate-pulse rounded-full bg-amber-500 motion-reduce:animate-none" />
          Pending approval
        </span>
        <button
          type="button"
          onClick={onReview}
          aria-label={`Review ${name}`}
          className={cn(
            'inline-flex items-center gap-1.5 rounded-xl bg-gradient-to-r px-4 py-2 text-sm font-semibold text-white shadow-md transition-all duration-200',
            'hover:shadow-lg hover:brightness-110 focus-visible:outline-2 focus-visible:outline-offset-2 active:scale-[0.98]',
            look.gradient,
            look.shadow,
            look.focus,
          )}
        >
          Review
          <Icon name="arrowRight" className="size-4 transition-transform duration-200 group-hover:translate-x-0.5" />
        </button>
      </div>
    </article>
  )
}

function SkeletonCards() {
  return (
    <div className="grid grid-cols-1 gap-4 md:grid-cols-2 2xl:grid-cols-3" aria-hidden="true">
      {[0, 1, 2].map((index) => (
        <div key={index} className="rounded-2xl bg-white p-5 ring-1 ring-slate-200/80">
          <div className="flex items-start gap-4">
            <Skeleton className="size-12 rounded-2xl" />
            <div className="flex-1 space-y-2">
              <Skeleton className="h-4 w-2/3" />
              <Skeleton className="h-3 w-1/2" />
            </div>
          </div>
          <div className="mt-5 flex gap-2">
            <Skeleton className="h-6 w-28 rounded-full" />
            <Skeleton className="h-6 w-36 rounded-full" />
          </div>
          <div className="mt-6 flex justify-end">
            <Skeleton className="h-9 w-24 rounded-xl" />
          </div>
        </div>
      ))}
    </div>
  )
}

/** Nothing to review: a calm, positive state instead of an empty table. */
function AllCaughtUp({ plural, accent }: { plural: string; accent: ApprovalAccent }) {
  const look = ACCENTS[accent]
  return (
    <div className="relative overflow-hidden rounded-3xl bg-white px-6 py-14 text-center ring-1 ring-slate-200/80">
      <div aria-hidden="true" className={cn('absolute -top-20 left-1/2 size-56 -translate-x-1/2 rounded-full blur-3xl', look.glow)} />
      <span className="relative mx-auto flex size-16 animate-float items-center justify-center rounded-3xl bg-white shadow-lg ring-1 ring-slate-100 motion-reduce:animate-none">
        <Icon name="checkCircle" className="size-9" />
      </span>
      <h3 className="relative mt-5 text-lg font-semibold text-slate-900">All caught up!</h3>
      <p className="relative mx-auto mt-1 max-w-sm text-sm text-slate-500">
        No {plural.toLowerCase()} are waiting for approval. New submissions appear here, oldest first.
      </p>
    </div>
  )
}

interface PendingQueueProps<T extends StakeholderLifecycle> {
  kind: StakeholderKind<T>
  /** The colour family of this stakeholder type (cards, buttons). */
  accent?: ApprovalAccent
}

/**
 * Submissions waiting for review (status = PENDING_APPROVAL), oldest first, as cards; "Review" opens the drawer where
 * an authorised admin approves or rejects with a reason.
 */
export function PendingQueue<T extends StakeholderLifecycle>({ kind, accent = 'amber' }: PendingQueueProps<T>) {
  const [page, setPage] = useState(0)
  const [selected, setSelected] = useState<T | null>(null)
  const query = useStakeholderList(kind, { status: 'PENDING_APPROVAL', page, size: DEFAULT_PAGE_SIZE })
  const now = useNow(60_000)

  let body: ReactNode
  if (query.isPending) {
    body = (
      <>
        <span className="sr-only" role="status">
          Loading {kind.plural.toLowerCase()}
        </span>
        <SkeletonCards />
      </>
    )
  } else if (query.isError || !query.data) {
    body = (
      <ErrorState
        title={`Could not load ${kind.plural.toLowerCase()}`}
        message={query.error?.message ?? 'Unknown error'}
        correlationId={query.error instanceof ApiError ? query.error.correlationId : null}
        onRetry={() => void query.refetch()}
      />
    )
  } else if (query.data.content.length === 0) {
    body = <AllCaughtUp plural={kind.plural} accent={accent} />
  } else {
    const data = query.data
    body = (
      <>
        <ul
          aria-label={`${kind.plural} waiting for approval`}
          aria-busy={query.isFetching && query.isPlaceholderData}
          className={cn('grid grid-cols-1 gap-4 md:grid-cols-2 2xl:grid-cols-3', query.isFetching && query.isPlaceholderData && 'opacity-60')}
        >
          {data.content.map((item, index) => (
            <li key={item.id} className="min-w-0 animate-fade-up motion-reduce:animate-none" style={{ animationDelay: `${Math.min(index, 8) * 45}ms` }}>
              <SubmissionCard
                name={kind.displayName(item)}
                summary={kind.summary(item)}
                submittedAt={item.submittedAt}
                now={now}
                accent={accent}
                onReview={() => setSelected(item)}
              />
            </li>
          ))}
        </ul>
        <Pagination
          page={data.page}
          size={data.size}
          totalElements={data.totalElements}
          totalPages={data.totalPages}
          onPageChange={setPage}
          disabled={query.isFetching}
        />
      </>
    )
  }

  return (
    <>
      {body}
      {selected && <StakeholderDetailDrawer kind={kind} id={selected.id} initial={selected} onClose={() => setSelected(null)} />}
    </>
  )
}
