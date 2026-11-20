import { useState } from 'react'
import { ApiError } from '../../shared/api/problemDetail'
import { useAuth } from '../../shared/auth/useAuth'
import { Button, EmptyState, ErrorState, PageHeader, Pagination, SelectField, Spinner } from '../../shared/components/ui'
import { ExportButton } from '../../shared/export'
import { pageFromParam, pageToParam, useSearchParamsUpdater } from '../../shared/hooks/useSearchParamsUpdater'
import type { ModeratedReviewView } from './api'
import { useStoreRatingSummary, useStoreReviews } from './hooks'
import { REVIEW_TARGET_LABELS } from './labels'
import { ReplyDialog } from './ReplyDialog'
import { RatingSummaryPanel, ReviewCard } from './ReviewBits'

const PAGE_SIZE = 10

const KIND = {
  VENDOR: { storeType: 'VENDOR', itemType: 'PRODUCT', itemsLabel: 'Products', title: 'Reviews' },
  RESTAURANT: { storeType: 'RESTAURANT', itemType: 'MENU_ITEM', itemsLabel: 'Dishes', title: 'Reviews' },
} as const

/**
 * `/vendor/reviews` and `/restaurant/reviews` (Phase 12 §1) — published reviews of the store and of its products or
 * dishes, newest first, filterable by what they are about and by stars (in the URL), with the store's average
 * rating. The owner writes one public reply per review and may edit it.
 */
export function StoreReviewsView({ kind }: { kind: 'VENDOR' | 'RESTAURANT' }) {
  const config = KIND[kind]
  const { user } = useAuth()
  const storeId = (kind === 'VENDOR' ? user?.vendorId : user?.restaurantId) ?? null
  const [searchParams, updateParams] = useSearchParamsUpdater()
  const [replyTo, setReplyTo] = useState<ModeratedReviewView | null>(null)
  const targetParam = searchParams.get('about')
  const targetType = targetParam === config.storeType || targetParam === config.itemType ? targetParam : undefined
  const ratingParam = Number(searchParams.get('rating'))
  const rating = Number.isInteger(ratingParam) && ratingParam >= 1 && ratingParam <= 5 ? ratingParam : undefined
  const page = pageFromParam(searchParams.get('page'))
  const reviews = useStoreReviews({ targetType, rating, page, size: PAGE_SIZE })
  const summary = useStoreRatingSummary(config.storeType, storeId)

  return (
    <div className="space-y-4">
      <PageHeader
        icon="star"
        title={config.title}
        description="What customers say about your store and its items. Reply publicly — one reply per review, editable."
        actions={
          <ExportButton path="/store/reviews/export" label="reviews" params={{ targetType, rating }} total={reviews.data?.totalElements} />
        }
      />
      {summary.data && <RatingSummaryPanel summary={summary.data} label="Store rating" />}

      <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
        <SelectField label="About" value={targetType ?? ''} onChange={(event) => updateParams({ about: event.target.value, page: null })}>
          <option value="">Everything</option>
          <option value={config.storeType}>The {REVIEW_TARGET_LABELS[config.storeType].toLowerCase()}</option>
          <option value={config.itemType}>{config.itemsLabel}</option>
        </SelectField>
        <SelectField label="Stars" value={rating ? String(rating) : ''} onChange={(event) => updateParams({ rating: event.target.value, page: null })}>
          <option value="">Any rating</option>
          {[5, 4, 3, 2, 1].map((stars) => (
            <option key={stars} value={stars}>
              {stars} star{stars === 1 ? '' : 's'}
            </option>
          ))}
        </SelectField>
      </div>

      {reviews.isPending ? (
        <div className="flex justify-center py-12">
          <Spinner size="lg" label="Loading reviews" />
        </div>
      ) : reviews.isError ? (
        <ErrorState
          title="Could not load reviews"
          message={reviews.error.message}
          correlationId={reviews.error instanceof ApiError ? reviews.error.correlationId : null}
          onRetry={() => void reviews.refetch()}
        />
      ) : reviews.data.content.length === 0 ? (
        <EmptyState
          title="No reviews yet"
          description={targetType || rating ? 'None match these filters.' : 'Customers can review an order for a limited time after delivery.'}
        />
      ) : (
        <div>
          <ul aria-label="Reviews" className="space-y-3">
            {reviews.data.content.map((review) => (
              <li key={review.id}>
                <ReviewCard
                  review={review}
                  actions={
                    <Button size="sm" variant={review.reply ? 'ghost' : 'secondary'} onClick={() => setReplyTo(review)}>
                      {review.reply ? 'Edit reply' : 'Reply'}
                    </Button>
                  }
                />
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

      <ReplyDialog review={replyTo} onClose={() => setReplyTo(null)} />
    </div>
  )
}
