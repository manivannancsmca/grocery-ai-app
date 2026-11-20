import { Link } from 'react-router'
import { MY_REVIEW_PAGE_SIZE } from '../../../features/reviews/api'
import { useEligibleReviews, useMyReviews } from '../../../features/reviews/hooks'
import { ReviewEditor } from '../../../features/reviews/ReviewEditor'
import { ApiError } from '../../../shared/api/problemDetail'
import { EmptyState, ErrorState, Pagination, Skeleton } from '../../../shared/components/ui'
import { ExportButton } from '../../../shared/export'
import { useNow } from '../../../shared/hooks/useNow'
import { pageFromParam, pageToParam, useSearchParamsUpdater } from '../../../shared/hooks/useSearchParamsUpdater'
import { Icon } from '../../../shared/icons'
import { ReviewsIllustration } from '../../../shared/illustrations'
import { cn } from '../../../shared/utils/cn'
import { formatDate } from '../../../shared/utils/format'
import { ShopperPageHeader } from '../components/ShopperPageHeader'

/** @typedef {import('../../../features/reviews/api').EligibleReviewTarget} EligibleReviewTarget */

const linkClass =
  'inline-flex h-10 items-center justify-center gap-2 rounded-xl bg-emerald-600 px-4 text-sm font-semibold text-white shadow-sm shadow-emerald-600/25 transition-colors hover:bg-emerald-700 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-emerald-600'
const rateLink =
  'inline-flex h-9 shrink-0 items-center justify-center gap-1.5 rounded-full bg-amber-500 px-4 text-sm font-semibold text-white shadow-sm shadow-amber-500/25 transition-colors hover:bg-amber-600 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-amber-600'

/**
 * @typedef {object} WaitingOrder
 * @property {string} orderId
 * @property {string} orderNumber
 * @property {string} deliveredAt
 * @property {number} unrated
 */

/**
 * Delivered orders (still in the 30-day window) with something not rated yet, newest first.
 * @param {readonly EligibleReviewTarget[]} targets
 * @returns {WaitingOrder[]}
 */
function waitingOrders(targets) {
  const byOrder = new Map()
  for (const target of targets) {
    if (target.review) continue
    const entry = byOrder.get(target.orderId) ?? {
      orderId: target.orderId,
      orderNumber: target.orderNumber,
      deliveredAt: target.deliveredAt,
      unrated: 0,
    }
    entry.unrated += 1
    byOrder.set(target.orderId, entry)
  }
  return [...byOrder.values()]
}

/** @param {{ orders: readonly WaitingOrder[] }} props */
function WaitingForReview({ orders }) {
  if (orders.length === 0) return null
  return (
    <section
      aria-labelledby="waiting-reviews"
      className="animate-fade-up relative mb-8 overflow-hidden rounded-2xl border border-amber-200/80 bg-gradient-to-br from-amber-50 via-orange-50/40 to-white p-4 shadow-sm sm:p-5"
    >
      <div
        aria-hidden="true"
        className="pointer-events-none absolute -top-16 -right-10 size-48 rounded-full bg-amber-200/40 blur-2xl"
      />
      <div className="relative flex items-center gap-3">
        <span
          aria-hidden="true"
          className="flex size-11 shrink-0 items-center justify-center rounded-xl bg-white shadow-sm ring-1 ring-amber-200"
        >
          <Icon name="starPlus" className="size-6" />
        </span>
        <div>
          <h2 id="waiting-reviews" className="text-base font-semibold text-slate-900">
            Waiting for your review
          </h2>
          <p className="text-sm text-slate-600">Your ratings help other shoppers choose — it takes a few seconds.</p>
        </div>
      </div>
      <ul className="relative mt-4 grid gap-3 md:grid-cols-2">
        {orders.map((order) => (
          <li
            key={order.orderId}
            className="flex items-center justify-between gap-3 rounded-xl bg-white/90 px-4 py-3 shadow-xs ring-1 ring-amber-100 backdrop-blur-sm"
          >
            <span className="flex min-w-0 items-center gap-3">
              <span
                aria-hidden="true"
                className="flex size-9 shrink-0 items-center justify-center rounded-lg bg-amber-50"
              >
                <Icon name="box" className="size-5" />
              </span>
              <span className="min-w-0 text-sm">
                <span className="block truncate font-semibold text-slate-900">
                  Order <span className="font-mono">{order.orderNumber}</span>
                </span>
                <span className="flex flex-wrap items-center gap-1.5 text-xs text-slate-500">
                  Delivered {formatDate(order.deliveredAt)}
                  <span className="rounded-full bg-amber-100 px-1.5 py-px font-semibold text-amber-800">
                    {order.unrated} to rate
                  </span>
                </span>
              </span>
            </span>
            <Link
              to={`/orders/${encodeURIComponent(order.orderId)}/review`}
              className={rateLink}
              aria-label={`Rate order ${order.orderNumber}`}
            >
              <Icon name="star" className="size-4" />
              Rate now
            </Link>
          </li>
        ))}
      </ul>
    </section>
  )
}

/** `/account/reviews` — the customer's reviews in every state, with edit (7 days) and delete; orders waiting for one. */
export function MyReviewsPage() {
  const [searchParams, updateParams] = useSearchParamsUpdater()
  const page = pageFromParam(searchParams.get('page'))
  const reviews = useMyReviews({ page, size: MY_REVIEW_PAGE_SIZE })
  const eligible = useEligibleReviews(null)
  const waiting = waitingOrders(eligible.data ?? [])
  const now = useNow(60_000)

  const stats = []
  if (reviews.data)
    stats.push({ label: 'Reviews written', value: reviews.data.totalElements, icon: 'star', tone: 'amber' })
  if (eligible.data)
    stats.push({
      label: 'Waiting to be rated',
      value: waiting.reduce((sum, order) => sum + order.unrated, 0),
      icon: 'starPlus',
      tone: 'emerald',
    })

  let content
  if (reviews.isPending) {
    content = (
      <div role="status" aria-label="Loading your reviews" className="space-y-4">
        <Skeleton className="h-40 w-full rounded-2xl" />
        <Skeleton className="h-40 w-full rounded-2xl" />
      </div>
    )
  } else if (reviews.isError) {
    content = (
      <ErrorState
        title="Could not load your reviews"
        message={reviews.error.message}
        correlationId={reviews.error instanceof ApiError ? reviews.error.correlationId : null}
        onRetry={() => void reviews.refetch()}
      />
    )
  } else if (reviews.data.content.length === 0) {
    content = (
      <EmptyState
        size="lg"
        illustration={<ReviewsIllustration className="animate-float" />}
        title="You have not written any reviews yet"
        description="After an order is delivered you can rate its items, the store and the delivery for a limited time."
        action={
          <Link to="/orders?status=DELIVERED" className={linkClass}>
            See delivered orders
          </Link>
        }
      />
    )
  } else {
    content = (
      <>
        <h2 className="mb-3 text-base font-semibold text-slate-900">Your reviews</h2>
        <div
          aria-label="Your reviews"
          role="list"
          className={cn('space-y-4 transition-opacity', reviews.isPlaceholderData && 'opacity-60')}
        >
          {reviews.data.content.map((review) => (
            <div role="listitem" key={review.id}>
              <ReviewEditor
                // A moderation decision or the store's reply arrives as a changed review: start from it again.
                key={[review.updatedAt ?? review.createdAt, review.status, review.reply?.repliedAt ?? ''].join(':')}
                orderId={review.orderId}
                targetType={review.targetType}
                targetId={review.targetId}
                targetName={review.targetName}
                review={review}
                now={now}
                allowCreate={false}
                headingLevel="h2"
                context={
                  review.orderNumber ? (
                    <>
                      Order{' '}
                      <Link
                        to={`/orders/${encodeURIComponent(review.orderId)}`}
                        className="font-mono font-medium text-emerald-700 hover:underline"
                      >
                        {review.orderNumber}
                      </Link>
                    </>
                  ) : undefined
                }
              />
            </div>
          ))}
        </div>
        <Pagination
          page={reviews.data.page}
          size={reviews.data.size}
          totalElements={reviews.data.totalElements}
          totalPages={reviews.data.totalPages}
          disabled={reviews.isFetching}
          onPageChange={(next) => updateParams({ page: pageToParam(next) })}
        />
      </>
    )
  }

  return (
    <div className="w-full">
      <ShopperPageHeader
        icon="star"
        eyebrow="Orders"
        title="My reviews"
        description="Reviews are published at once unless our team needs to check them. You can edit a review for 7 days."
        actions={<ExportButton path="/reviews/mine/export" label="your reviews" total={reviews.data?.totalElements} />}
        stats={stats}
      />
      <WaitingForReview orders={waiting} />
      {content}
    </div>
  )
}
