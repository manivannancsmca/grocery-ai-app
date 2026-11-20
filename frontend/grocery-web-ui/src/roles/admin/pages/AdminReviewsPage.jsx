import { useState } from 'react'
import { REVIEW_STATUSES, REVIEW_TARGET_TYPES } from '../../../features/reviews/api'
import { MODERATION_LIMITS, STORE_REVIEW_ERRORS } from '../../../features/store-reviews/api'
import { useAdminReviews, useModerateReview } from '../../../features/store-reviews/hooks'
import {
  moderationConflictMessage,
  REVIEW_STATUS_LABELS,
  REVIEW_TARGET_LABELS,
} from '../../../features/store-reviews/labels'
import { ReviewCard } from '../../../features/store-reviews/ReviewBits'
import { ApiError, errorMessage, hasErrorCode } from '../../../shared/api/problemDetail'
import { useAuth } from '../../../shared/auth/useAuth'
import { ReasonDialog } from '../../../shared/components/ReasonDialog'
import { useToast } from '../../../shared/components/toast/useToast'
import {
  Alert,
  Button,
  EmptyState,
  ErrorState,
  FilterBar,
  PageHeader,
  Pagination,
  SelectField,
  Skeleton,
  StatCard,
  TextField,
} from '../../../shared/components/ui'
import { ExportButton } from '../../../shared/export'
import { useDebouncedValue } from '../../../shared/hooks/useDebouncedValue'
import { pageFromParam, pageToParam, useSearchParamsUpdater } from '../../../shared/hooks/useSearchParamsUpdater'
import { Icon } from '../../../shared/icons'
import { cn } from '../../../shared/utils/cn'
import { formatCount } from '../../../shared/utils/format'

/** @typedef {import('../../../features/reviews/api').ReviewStatus} ReviewStatus */
/** @typedef {import('../../../features/store-reviews/api').ModeratedReviewView} ModeratedReviewView */
/** @typedef {import('../../../shared/components/ui').StatAccent} StatAccent */
/** @typedef {import('../../../shared/icons').IconName} IconName */

const PAGE_SIZE = 10

/**
 * @param {unknown} value
 * @returns {value is ReviewStatus}
 */
function isReviewStatus(value) {
  return typeof value === 'string' && REVIEW_STATUSES.includes(value)
}

/**
 * One review with the moderation its status allows: flagged → publish or reject; published → remove.
 * @param {{ review: ModeratedReviewView, canModerate: boolean }} props
 */
function ModeratedReview({ review, canModerate }) {
  const toast = useToast()
  const moderate = useModerateReview()
  const [dialog, setDialog] = useState(null)
  const [notice, setNotice] = useState(null)
  const busy = moderate.isPending

  /** A 409 explains itself on the card; other errors stay in the reason dialog (or on the card for publish). */
  async function run(action, reason) {
    setNotice(null)
    try {
      await moderate.mutateAsync({ id: review.id, action, reason })
      toast.success(
        action === 'publish' ? 'Review published' : action === 'reject' ? 'Review rejected' : 'Review removed',
      )
      setDialog(null)
    } catch (error) {
      if (hasErrorCode(error, STORE_REVIEW_ERRORS.notModeratable)) {
        setDialog(null)
        setNotice(moderationConflictMessage(error))
        return
      }
      if (action === 'publish') setNotice(errorMessage(error))
      else throw error
    }
  }

  // The decisions stay visible buttons (not a "⋯" menu): moderating is the whole point of this page.
  const actions =
    canModerate && review.status === 'FLAGGED' ? (
      <>
        <Button size="sm" onClick={() => void run('publish')} disabled={busy}>
          <Icon name="check" className="size-4" />
          Publish
        </Button>
        <Button size="sm" variant="secondary" onClick={() => setDialog('reject')} disabled={busy}>
          <Icon name="ban" className="size-4" />
          Reject
        </Button>
      </>
    ) : canModerate && review.status === 'PUBLISHED' ? (
      <Button size="sm" variant="secondary" onClick={() => setDialog('remove')} disabled={busy}>
        <Icon name="trash" className="size-4" />
        Remove
      </Button>
    ) : null

  return (
    <>
      <ReviewCard
        review={review}
        moderation
        actions={actions}
        notice={
          notice && (
            <Alert tone="warning" role="alert" className="mt-3">
              {notice}
            </Alert>
          )
        }
      />
      <ReasonDialog
        open={dialog !== null}
        title={dialog === 'remove' ? 'Remove this published review?' : 'Reject this flagged review?'}
        description="It stays hidden. The customer is told it was not published, with your reason."
        confirmLabel={dialog === 'remove' ? 'Remove review' : 'Reject review'}
        cancelLabel="Back"
        maxLength={MODERATION_LIMITS.reason}
        onClose={() => setDialog(null)}
        onSubmit={(reason) => run(dialog ?? 'reject', reason)}
      />
    </>
  )
}

/**
 * The picture and colour of the headline tile for each status.
 * @type {Record<ReviewStatus, { icon: IconName, accent: StatAccent }>}
 */
const STATUS_LOOK = {
  FLAGGED: { icon: 'starFlag', accent: 'amber' },
  PUBLISHED: { icon: 'star', accent: 'emerald' },
  REJECTED: { icon: 'starCross', accent: 'rose' },
  REMOVED: { icon: 'starCross', accent: 'rose' },
}

/**
 * @typedef {object} ReviewStatsProps
 * @property {ReviewStatus} status
 * @property {number} total
 * @property {readonly ModeratedReviewView[]} rows
 * @property {boolean} filtered
 */

/**
 * Headline numbers: how many reviews have this status, and the stars and replies of the reviews on this page.
 * @param {ReviewStatsProps} props
 */
function ReviewStats({ status, total, rows, filtered }) {
  const average = rows.length > 0 ? rows.reduce((sum, review) => sum + review.rating, 0) / rows.length : null
  const replies = rows.filter((review) => review.reply).length
  const look = STATUS_LOOK[status]
  return (
    <section aria-label="Reviews summary" className="animate-fade-up motion-reduce:animate-none">
      {/* Two tiles per row on phones, the third on a row of its own; three in a row on wide screens. */}
      <dl className="grid grid-cols-2 gap-3 lg:grid-cols-3">
        <StatCard
          label={`${REVIEW_STATUS_LABELS[status]} reviews`}
          value={formatCount(total)}
          hint={filtered ? 'Matching the filters' : 'All stores'}
          icon={look.icon}
          accent={look.accent}
        />
        <StatCard
          label="Average on this page"
          value={average === null ? '—' : `${average.toFixed(1)} / 5`}
          hint="Stars given by the customers"
          icon="star"
          accent="violet"
        />
        <StatCard
          label="Store replies on this page"
          value={formatCount(replies)}
          hint={`Of ${formatCount(rows.length)} reviews`}
          icon="chat"
          accent="sky"
          className="col-span-2 lg:col-span-1"
        />
      </dl>
    </section>
  )
}

/** Placeholder cards while the first page loads. */
function SkeletonCards() {
  return (
    <div className="grid gap-4 xl:grid-cols-2" aria-hidden="true">
      {[0, 1].map((index) => (
        <div key={index} className="rounded-2xl bg-white p-5 ring-1 ring-slate-200/80">
          <div className="flex items-start gap-3">
            <Skeleton className="size-10 rounded-xl" />
            <div className="flex-1 space-y-2">
              <Skeleton className="h-4 w-1/3" />
              <Skeleton className="h-3 w-1/4" />
            </div>
          </div>
          <Skeleton className="mt-4 h-4 w-24" />
          <Skeleton className="mt-3 h-4 w-2/3" />
          <Skeleton className="mt-2 h-3 w-full" />
          <Skeleton className="mt-4 h-6 w-1/2 rounded-lg" />
        </div>
      ))}
    </div>
  )
}

/**
 * `/admin/reviews` (Phase 12 §1) — review moderation. Flagged reviews (links, phone numbers, profanity, shouting)
 * are hidden until an admin publishes or rejects them; published ones can be removed. Every admin may read;
 * super, operations admins and support moderate. Filters live in the URL.
 */
export function AdminReviewsPage() {
  const { can } = useAuth()
  const [searchParams, updateParams] = useSearchParamsUpdater()
  const statusParam = searchParams.get('status')
  const status = isReviewStatus(statusParam) ? statusParam : 'FLAGGED'
  const targetParam = searchParams.get('targetType')
  const targetType = targetParam && REVIEW_TARGET_TYPES.includes(targetParam) ? targetParam : undefined
  const queryText = searchParams.get('q') ?? ''
  const q = useDebouncedValue(queryText.trim(), 300)
  const page = pageFromParam(searchParams.get('page'))
  const reviews = useAdminReviews({ status, targetType, q: q || undefined, page, size: PAGE_SIZE })
  const canModerate = can('reviews.moderate')
  // "Flagged" is the default status, so only another status counts as a filter.
  const activeFilters = (status !== 'FLAGGED' ? 1 : 0) + (targetType ? 1 : 0) + (queryText.trim() ? 1 : 0)
  const refreshing = reviews.isFetching && reviews.isPlaceholderData

  return (
    <div className="space-y-5">
      <PageHeader
        icon="star"
        title="Reviews"
        description="Moderate customer reviews. Flagged ones stay hidden until you decide."
        actions={
          <ExportButton
            path="/admin/reviews/export"
            label="reviews"
            params={{ status, targetType, q: q || undefined }}
            total={reviews.data?.totalElements}
          />
        }
      />
      <FilterBar
        activeCount={activeFilters}
        onClear={() => updateParams({ status: null, targetType: null, q: null, page: null })}
      >
        <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
          <SelectField
            label="Status"
            value={status}
            onChange={(event) =>
              updateParams({ status: event.target.value === 'FLAGGED' ? null : event.target.value, page: null })
            }
          >
            {REVIEW_STATUSES.map((value) => (
              <option key={value} value={value}>
                {REVIEW_STATUS_LABELS[value]}
              </option>
            ))}
          </SelectField>
          <SelectField
            label="About"
            value={targetType ?? ''}
            onChange={(event) => updateParams({ targetType: event.target.value, page: null })}
          >
            <option value="">Everything</option>
            {REVIEW_TARGET_TYPES.map((value) => (
              <option key={value} value={value}>
                {REVIEW_TARGET_LABELS[value]}
              </option>
            ))}
          </SelectField>
          <TextField
            label="Search"
            type="search"
            value={queryText}
            placeholder="Words, store or product"
            autoComplete="off"
            containerClassName="sm:col-span-2"
            onChange={(event) => updateParams({ q: event.target.value, page: null }, { replace: true })}
          />
        </div>
      </FilterBar>
      {!canModerate && (
        <p className="flex items-center gap-2 text-sm text-slate-500">
          <Icon name="info" className="size-4" />
          Your role can read reviews; support and operations admins moderate them.
        </p>
      )}

      {reviews.data && (
        <ReviewStats
          status={status}
          total={reviews.data.totalElements}
          rows={reviews.data.content}
          filtered={activeFilters > 0}
        />
      )}

      {reviews.isPending ? (
        <>
          <span className="sr-only" role="status">
            Loading reviews
          </span>
          <SkeletonCards />
        </>
      ) : reviews.isError ? (
        <ErrorState
          title="Could not load reviews"
          message={reviews.error.message}
          correlationId={reviews.error instanceof ApiError ? reviews.error.correlationId : null}
          onRetry={() => void reviews.refetch()}
        />
      ) : reviews.data.content.length === 0 ? (
        <EmptyState
          title={status === 'FLAGGED' && !targetType && !q ? 'Nothing to moderate' : 'No reviews found'}
          description={
            status === 'FLAGGED' ? 'Flagged reviews appear here, and you are notified.' : 'Try different filters.'
          }
        />
      ) : (
        <div>
          <ul
            aria-label="Reviews"
            aria-busy={refreshing}
            className={cn('grid gap-4 transition-opacity xl:grid-cols-2', refreshing && 'opacity-60')}
          >
            {reviews.data.content.map((review, index) => (
              // A short stagger so the cards arrive one after another.
              <li
                key={review.id}
                className="min-w-0 animate-fade-up motion-reduce:animate-none"
                style={{ animationDelay: `${Math.min(index, 8) * 45}ms` }}
              >
                <ModeratedReview review={review} canModerate={canModerate} />
              </li>
            ))}
          </ul>
          <Pagination
            page={reviews.data.page}
            size={reviews.data.size}
            totalElements={reviews.data.totalElements}
            totalPages={reviews.data.totalPages}
            disabled={reviews.isFetching}
            onPageChange={(next) => updateParams({ page: pageToParam(next) })}
          />
        </div>
      )}
    </div>
  )
}
