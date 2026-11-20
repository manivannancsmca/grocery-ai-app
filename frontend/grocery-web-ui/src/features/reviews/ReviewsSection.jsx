import { useId, useState } from 'react'
import { Button, Pagination, Select, Skeleton } from '../../shared/components/ui'
import { cn } from '../../shared/utils/cn'
import { formatDate, initials } from '../../shared/utils/format'
import { PUBLIC_REVIEW_PAGE_SIZE, PUBLIC_REVIEW_SORTS } from './api'
import { usePublicReviews, useRatingSummary } from './hooks'
import { formatRating, ratingCountLabel, starsLabel } from './labels'
import { ReviewReplyBlock } from './ReviewEditor'
import { Stars } from './StarRating'

/** @typedef {import('./api').PublicReview} PublicReview */
/** @typedef {import('./api').PublicReviewSort} PublicReviewSort */
/** @typedef {import('./api').RatingSummary} RatingSummary */

/** @type {Record<PublicReviewSort, string>} */
const SORT_LABELS = {
  newest: 'Newest first',
  highest: 'Highest rated',
  lowest: 'Lowest rated',
}

/**
 * @param {string} value
 * @returns {value is PublicReviewSort}
 */
function isSort(value) {
  return PUBLIC_REVIEW_SORTS.includes(value)
}

/**
 * Average, stars and count, plus one bar per star (5 → 1).
 * @param {{ summary: RatingSummary }} props
 */
function SummaryBlock({ summary }) {
  const counts = [1, 2, 3, 4, 5].map((star) => summary.distribution[star - 1] ?? 0)
  const max = Math.max(...counts, 1)
  return (
    <div className="flex flex-col gap-5 sm:flex-row sm:items-center">
      <div className="flex shrink-0 flex-col items-center justify-center rounded-2xl bg-gradient-to-br from-amber-50 to-orange-50 px-6 py-5 text-center ring-1 ring-amber-100 sm:w-44">
        <p className="text-4xl font-bold tracking-tight text-slate-900 tabular-nums">
          {formatRating(summary.averageRating)}
        </p>
        <Stars value={summary.averageRating ?? 0} size="md" className="mt-1.5" />
        <p className="mt-1.5 text-sm text-slate-600">{ratingCountLabel(summary.ratingCount)}</p>
      </div>
      <ul aria-label="Rating distribution" className="min-w-0 flex-1 space-y-2">
        {[5, 4, 3, 2, 1].map((star) => {
          const count = counts[star - 1] ?? 0
          return (
            <li key={star} className="flex items-center gap-2.5 text-xs text-slate-600">
              <span aria-hidden="true" className="flex w-8 shrink-0 items-center gap-0.5 font-medium tabular-nums">
                {star}
                <StarGlyph className="size-3.5 text-amber-400" />
              </span>
              <span aria-hidden="true" className="h-2.5 flex-1 overflow-hidden rounded-full bg-slate-100">
                <span
                  className="block h-full rounded-full bg-gradient-to-r from-amber-400 to-amber-500"
                  style={{ width: `${(count / max) * 100}%` }}
                />
              </span>
              <span aria-hidden="true" className="w-10 shrink-0 text-right tabular-nums">
                {count.toLocaleString('en-IN')}
              </span>
              <span className="sr-only">
                {starsLabel(star)}: {ratingCountLabel(count)}
              </span>
            </li>
          )
        })}
      </ul>
    </div>
  )
}

/** @param {{ className?: string }} props */
function StarGlyph({ className }) {
  return (
    <svg className={className} viewBox="0 0 20 20" fill="currentColor" aria-hidden="true">
      <path d="M10 1.6 12.6 6.9l5.8.8-4.2 4.1 1 5.8L10 14.9l-5.2 2.7 1-5.8L1.6 7.7l5.8-.8L10 1.6Z" />
    </svg>
  )
}

/** @param {{ review: PublicReview, storeName?: string | null }} props */
function PublicReviewItem({ review, storeName }) {
  return (
    <li className="py-5 first:pt-0 last:pb-0">
      <article aria-label={`Review by ${review.authorName}`} className="flex gap-3.5">
        <span
          aria-hidden="true"
          className="flex size-10 shrink-0 items-center justify-center rounded-full bg-gradient-to-br from-emerald-100 to-emerald-200 text-sm font-bold text-emerald-800"
        >
          {initials(review.authorName)}
        </span>
        <div className="min-w-0 flex-1">
          <div className="flex flex-wrap items-center justify-between gap-x-3 gap-y-1">
            <p className="text-sm font-semibold text-slate-900">{review.authorName}</p>
            <p className="text-xs text-slate-500">
              <time dateTime={review.createdAt}>{formatDate(review.createdAt)}</time>
              {review.edited && ' · Edited'}
            </p>
          </div>
          <div className="mt-1 flex flex-wrap items-center gap-x-2 gap-y-1">
            <Stars value={review.rating} />
            {review.title && <h3 className="text-sm font-semibold break-words text-slate-900">{review.title}</h3>}
          </div>
          {review.comment && (
            <p className="mt-1.5 text-sm leading-relaxed whitespace-pre-line break-words text-slate-700">
              {review.comment}
            </p>
          )}
          {review.reply && <ReviewReplyBlock reply={review.reply} storeName={storeName} />}
        </div>
      </article>
    </li>
  )
}

/**
 * @typedef {object} ReviewsSectionProps
 * @property {'PRODUCT' | 'MENU_ITEM' | 'VENDOR' | 'RESTAURANT' | 'DELIVERY_PARTNER'} targetType
 * @property {string} targetId
 * @property {string | null} [storeName] Who replies (the vendor or restaurant), for "Reply from …".
 * @property {string} [id] Anchor for "★ 4.3 (120 ratings)" links.
 * @property {string} [className]
 */

/**
 * "Ratings & reviews" of a product or restaurant (public): the summary with the distribution, then the published
 * reviews (newest / highest / lowest, paged) with the store's replies. A failure stays inside the section.
 * @param {ReviewsSectionProps} props
 */
export function ReviewsSection({ targetType, targetId, storeName, id, className }) {
  const headingId = useId()
  const sortId = useId()
  const [sort, setSort] = useState('newest')
  const [page, setPage] = useState(0)
  const summary = useRatingSummary(targetType, targetId)
  const reviews = usePublicReviews(targetType, targetId, { sort, page, size: PUBLIC_REVIEW_PAGE_SIZE })

  const ratingCount = summary.data?.ratingCount ?? 0
  const hasReviews = (reviews.data?.totalElements ?? 0) > 0

  let summaryBlock
  if (summary.isPending) {
    summaryBlock = <Skeleton className="h-24 w-full" />
  } else if (summary.isError) {
    summaryBlock = null
  } else if (ratingCount === 0) {
    summaryBlock = (
      <div className="flex items-center gap-3 rounded-xl bg-slate-50 px-4 py-3.5 ring-1 ring-slate-100">
        <span
          aria-hidden="true"
          className="flex size-10 shrink-0 items-center justify-center rounded-full bg-white text-amber-400 shadow-xs"
        >
          <StarGlyph className="size-5" />
        </span>
        <p className="text-sm text-slate-600">No ratings yet. Customers can rate it after their order is delivered.</p>
      </div>
    )
  } else {
    summaryBlock = <SummaryBlock summary={summary.data} />
  }

  let list
  if (reviews.isPending) {
    list = (
      <div role="status" aria-label="Loading reviews" className="space-y-3">
        <Skeleton className="h-16 w-full" />
        <Skeleton className="h-16 w-full" />
      </div>
    )
  } else if (reviews.isError) {
    list = (
      <div className="flex flex-wrap items-center gap-3 rounded-xl bg-slate-50 px-4 py-3 text-sm text-slate-600 ring-1 ring-slate-100">
        <span>Reviews could not be loaded right now.</span>
        <Button
          variant="ghost"
          size="sm"
          onClick={() => {
            void reviews.refetch()
            void summary.refetch()
          }}
        >
          Reload reviews
        </Button>
      </div>
    )
  } else if (!hasReviews) {
    list =
      ratingCount > 0 ? (
        <p className="rounded-xl bg-slate-50 px-4 py-3 text-sm text-slate-500">No written reviews yet.</p>
      ) : null
  } else {
    list = (
      <>
        <ul
          aria-label="Reviews"
          className={cn(
            'divide-y divide-slate-100 border-t border-slate-100 pt-5 transition-opacity',
            reviews.isPlaceholderData && 'opacity-60',
          )}
        >
          {reviews.data.content.map((review) => (
            <PublicReviewItem key={review.id} review={review} storeName={storeName} />
          ))}
        </ul>
        <Pagination
          page={reviews.data.page}
          size={reviews.data.size}
          totalElements={reviews.data.totalElements}
          totalPages={reviews.data.totalPages}
          disabled={reviews.isFetching}
          onPageChange={setPage}
        />
      </>
    )
  }

  return (
    <section
      id={id}
      aria-labelledby={headingId}
      className={cn(
        'scroll-mt-24 space-y-5 rounded-2xl border border-slate-200/80 bg-white p-5 shadow-sm sm:p-6',
        className,
      )}
    >
      <div className="flex flex-wrap items-center justify-between gap-3">
        <div className="flex items-center gap-3">
          <span
            aria-hidden="true"
            className="flex size-9 shrink-0 items-center justify-center rounded-xl bg-amber-50 text-amber-500"
          >
            <StarGlyph className="size-5" />
          </span>
          <h2 id={headingId} className="text-lg font-semibold text-slate-900">
            Ratings &amp; reviews
          </h2>
        </div>
        {hasReviews && (
          <div className="flex items-center gap-2">
            <label htmlFor={sortId} className="text-sm whitespace-nowrap text-slate-600">
              Sort reviews
            </label>
            <Select
              id={sortId}
              className="h-10 w-40"
              value={sort}
              onChange={(event) => {
                if (!isSort(event.target.value)) return
                setSort(event.target.value)
                setPage(0)
              }}
            >
              {PUBLIC_REVIEW_SORTS.map((option) => (
                <option key={option} value={option}>
                  {SORT_LABELS[option]}
                </option>
              ))}
            </Select>
          </div>
        )}
      </div>
      {summaryBlock}
      {list}
    </section>
  )
}
