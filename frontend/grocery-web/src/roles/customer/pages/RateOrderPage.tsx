import { Link, useParams } from 'react-router'
import type { EligibleReviewTarget } from '../../../features/reviews/api'
import { useEligibleReviews } from '../../../features/reviews/hooks'
import { targetGroup, type TargetGroup } from '../../../features/reviews/labels'
import { ReviewEditor } from '../../../features/reviews/ReviewEditor'
import { ApiError } from '../../../shared/api/problemDetail'
import { EmptyState, ErrorState, PageHeader, Skeleton } from '../../../shared/components/ui'
import { useNow } from '../../../shared/hooks/useNow'
import { Icon, type IconName } from '../../../shared/icons'
import { ReviewsIllustration } from '../../../shared/illustrations'
import { cn } from '../../../shared/utils/cn'
import { formatDateTime } from '../../../shared/utils/format'

const linkClass =
  'inline-flex h-10 items-center justify-center gap-2 rounded-xl bg-emerald-600 px-4 text-sm font-semibold text-white shadow-sm shadow-emerald-600/25 transition-colors hover:bg-emerald-700 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-emerald-600'

const GROUPS: ReadonlyArray<{ id: TargetGroup; title: string; description: string; icon: IconName }> = [
  { id: 'items', title: 'What you ordered', description: 'Rate each product or dish.', icon: 'bag' },
  { id: 'store', title: 'The store', description: 'Packing, freshness and how the order was handled.', icon: 'store' },
  { id: 'delivery', title: 'The delivery', description: 'Your delivery partner.', icon: 'scooter' },
]

/** Keeps an editor's local state only while the server's review stays the same. */
function editorKey(target: EligibleReviewTarget): string {
  const review = target.review
  return [target.targetType, target.targetId, review?.id ?? 'new', review?.updatedAt ?? review?.createdAt ?? '', review?.status ?? '', review?.reply?.repliedAt ?? ''].join(':')
}

/**
 * `/orders/:orderId/review` — "Rate your order": stars and an optional text for each product or dish, the store and
 * the delivery partner of a delivered order (for the review window, 30 days by default). Written reviews can be edited for 7 days and deleted.
 */
export function RateOrderPage() {
  const { orderId = '' } = useParams()
  const eligible = useEligibleReviews(orderId)
  const now = useNow(60_000)
  const orderLink = `/orders/${encodeURIComponent(orderId)}`

  let content
  if (eligible.isPending) {
    content = (
      <div role="status" aria-label="Loading what you can rate" className="space-y-4">
        <Skeleton className="h-20 w-full rounded-2xl" />
        <Skeleton className="h-44 w-full rounded-2xl" />
        <Skeleton className="h-44 w-full rounded-2xl" />
      </div>
    )
  } else if (eligible.isError) {
    content = (
      <ErrorState
        title="Could not load this order’s reviews"
        message={eligible.error.message}
        correlationId={eligible.error instanceof ApiError ? eligible.error.correlationId : null}
        onRetry={() => void eligible.refetch()}
      />
    )
  } else if (eligible.data.length === 0) {
    content = (
      <EmptyState
        size="lg"
        title="Nothing to rate for this order"
        description="Orders can be rated for a limited time after they are delivered."
        action={
          <Link to={orderLink} className={linkClass}>
            Back to the order
          </Link>
        }
      />
    )
  } else {
    const targets = eligible.data
    const rated = targets.filter((target) => target.review !== null).length
    const done = rated === targets.length
    content = (
      <div className="space-y-8">
        <div className="animate-fade-up flex items-center gap-4 rounded-2xl border border-slate-200/80 bg-white p-4 shadow-sm">
          <span
            aria-hidden="true"
            className={cn(
              'flex size-11 shrink-0 items-center justify-center rounded-xl',
              done ? 'bg-emerald-100 text-emerald-700' : 'bg-amber-100 text-amber-600',
            )}
          >
            <Icon name={done ? 'checkCircle' : 'star'} className="size-5" />
          </span>
          <div className="min-w-0 flex-1">
            <p className="text-sm font-semibold text-slate-900" aria-live="polite">
              {done ? 'You have rated everything in this order. Thank you!' : `${rated} of ${targets.length} rated`}
            </p>
            <div aria-hidden="true" className="mt-2 h-2 overflow-hidden rounded-full bg-slate-100">
              <div
                className="h-full rounded-full bg-gradient-to-r from-amber-400 to-emerald-500 transition-[width] duration-500"
                style={{ width: `${(rated / targets.length) * 100}%` }}
              />
            </div>
          </div>
        </div>
        {GROUPS.map((group) => {
          const entries = targets.filter((target) => targetGroup(target.targetType) === group.id)
          if (entries.length === 0) return null
          return (
            <section key={group.id} aria-label={group.title} className="animate-fade-up space-y-4">
              <div className="flex items-center gap-3">
                <span aria-hidden="true" className="flex size-9 shrink-0 items-center justify-center rounded-xl bg-white shadow-sm ring-1 ring-slate-200/80">
                  <Icon name={group.icon} className="size-[1.15rem]" />
                </span>
                <div>
                  <h2 className="text-lg font-semibold text-slate-900">{group.title}</h2>
                  <p className="text-sm text-slate-500">{group.description}</p>
                </div>
              </div>
              <div className="grid gap-4">
                {entries.map((target) => (
                  <ReviewEditor
                    key={editorKey(target)}
                    orderId={target.orderId}
                    targetType={target.targetType}
                    targetId={target.targetId}
                    targetName={target.targetName}
                    review={target.review}
                    now={now}
                  />
                ))}
              </div>
            </section>
          )
        })}
      </div>
    )
  }

  const first = eligible.data?.[0]
  return (
    <div className="w-full">
      <Link
        to={orderLink}
        className="mb-4 inline-flex items-center gap-1.5 rounded-lg text-sm font-semibold text-slate-500 transition-colors hover:text-emerald-700 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-emerald-600"
      >
        <Icon name="arrowLeft" className="size-4" />
        Back to order
      </Link>
      <PageHeader
        eyebrow="Rate & review"
        title="Rate your order"
        description={
          first
            ? `Order ${first.orderNumber} · delivered ${formatDateTime(first.deliveredAt)}. Your first name and last initial are shown with published reviews.`
            : 'Your first name and last initial are shown with published reviews.'
        }
        illustration={<ReviewsIllustration />}
      />
      {content}
    </div>
  )
}
