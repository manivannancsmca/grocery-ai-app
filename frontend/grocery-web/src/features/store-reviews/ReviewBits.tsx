import { type ReactNode, useId } from 'react'
import { Avatar, Badge, type BadgeTone } from '../../shared/components/ui'
import { Icon, type IconName } from '../../shared/icons'
import { cn } from '../../shared/utils/cn'
import { formatDateTime } from '../../shared/utils/format'
import type { RatingSummary } from '../reviews/api'
import type { ModeratedReviewView } from './api'
import { flagReasonLabel, reviewStatusLabel, reviewStatusTone, reviewTargetLabel } from './labels'

/** Five stars, `rating` of them filled; one accessible name ("4 out of 5 stars"). */
export function Stars({ rating, className }: { rating: number; className?: string }) {
  const rounded = Math.round(rating)
  return (
    <span role="img" aria-label={`${rating} out of 5 stars`} className={cn('inline-flex text-amber-500', className)}>
      {[1, 2, 3, 4, 5].map((star) => (
        <svg key={star} className={cn('size-4', star > rounded && 'text-slate-200')} viewBox="0 0 20 20" fill="currentColor" aria-hidden="true">
          <path d="M10 1.5l2.6 5.3 5.9.9-4.3 4.1 1 5.8L10 14.9l-5.2 2.7 1-5.8L1.5 7.7l5.9-.9L10 1.5z" />
        </svg>
      ))}
    </span>
  )
}

/** Average, count and the 5★…1★ distribution of a target. */
export function RatingSummaryPanel({ summary, label }: { summary: RatingSummary; label: string }) {
  const counts = summary.distribution ?? []
  const most = Math.max(1, ...counts)
  return (
    <section
      aria-label={label}
      className="animate-fade-up flex flex-col gap-4 rounded-2xl border border-slate-200/70 bg-white p-5 shadow-sm motion-reduce:animate-none sm:flex-row sm:items-center"
    >
      <div className="rounded-2xl bg-gradient-to-br from-amber-50 to-orange-50 px-4 py-3 text-center ring-1 ring-amber-100 sm:w-44">
        <p className="text-4xl font-bold tracking-tight text-slate-900 tabular-nums">
          {summary.averageRating ? summary.averageRating.toFixed(2) : '—'}
        </p>
        {summary.averageRating ? <Stars rating={summary.averageRating} className="mt-1" /> : null}
        <p className="mt-1 text-sm text-slate-500">
          {summary.ratingCount} rating{summary.ratingCount === 1 ? '' : 's'}
        </p>
      </div>
      <dl className="flex-1 space-y-1">
        {[5, 4, 3, 2, 1].map((star) => {
          const count = counts[star - 1] ?? 0
          return (
            <div key={star} className="flex items-center gap-2 text-sm">
              <dt className="w-8 shrink-0 text-slate-600">{star}★</dt>
              <dd className="flex flex-1 items-center gap-2">
                <span className="h-2 flex-1 overflow-hidden rounded-full bg-slate-100" aria-hidden="true">
                  <span className="block h-full rounded-full bg-gradient-to-r from-amber-400 to-orange-400" style={{ width: `${(count / most) * 100}%` }} />
                </span>
                <span className="w-8 text-right text-slate-700 tabular-nums">{count}</span>
              </dd>
            </div>
          )
        })}
      </dl>
    </section>
  )
}

interface ReviewCardProps {
  review: ModeratedReviewView
  /** Admin view: status, flag reason and moderation reason. */
  moderation?: boolean
  /** Buttons under the review (reply, moderate). */
  actions?: ReactNode
  /** Shown under the actions (e.g. a refused moderation). */
  notice?: ReactNode
}

/** The colour bar on the left of a review card: by moderation status for admins, by the stars otherwise. */
const TONE_BARS: Record<BadgeTone, string> = {
  green: 'from-emerald-400 to-teal-500',
  amber: 'from-amber-400 to-orange-500',
  red: 'from-rose-400 to-red-500',
  gray: 'from-slate-300 to-slate-400',
  blue: 'from-sky-400 to-blue-500',
  violet: 'from-violet-400 to-fuchsia-500',
}

function ratingTone(rating: number): BadgeTone {
  if (rating >= 4) return 'green'
  return rating >= 3 ? 'amber' : 'red'
}

/** A small rounded label with a picture (what the review is about, the order, the store). */
function Chip({ icon, children }: { icon: IconName; children: ReactNode }) {
  return (
    <span className="inline-flex max-w-full min-w-0 items-center gap-1.5 rounded-lg bg-slate-50 px-2.5 py-1 text-slate-600 ring-1 ring-slate-200 ring-inset">
      <Icon name={icon} className="size-3.5" />
      <span className="min-w-0 break-words">{children}</span>
    </span>
  )
}

/** One review: stars, title, comment, who and when, what it is about, and the store's reply. */
export function ReviewCard({ review, moderation = false, actions, notice }: ReviewCardProps) {
  const titleId = useId()
  const flag = flagReasonLabel(review.flagReason)
  const heading = review.title?.trim() || `${review.rating}-star review`
  const author = review.authorName ?? 'A customer'
  const bar = TONE_BARS[moderation ? reviewStatusTone(review.status) : ratingTone(review.rating)]
  return (
    <article
      aria-labelledby={titleId}
      className="relative flex h-full flex-col overflow-hidden rounded-2xl bg-white p-5 pl-6 shadow-sm ring-1 ring-slate-200/80 transition-all duration-300 hover:-translate-y-0.5 hover:shadow-lg hover:shadow-slate-900/[0.08] motion-reduce:transition-none motion-reduce:hover:translate-y-0"
    >
      <span aria-hidden="true" className={cn('absolute inset-y-0 left-0 w-1 bg-gradient-to-b', bar)} />

      {/* Who wrote it and when, with the moderation status for admins. */}
      <div className="flex items-start gap-3">
        <Avatar name={review.authorName} />
        <div className="min-w-0 flex-1">
          <div className="flex flex-wrap items-start justify-between gap-2">
            <p className="min-w-0 truncate text-sm font-semibold text-slate-900">{author}</p>
            {moderation && (
              <Badge tone={reviewStatusTone(review.status)} dot>
                {reviewStatusLabel(review.status)}
              </Badge>
            )}
          </div>
          <p className="text-xs text-slate-500">
            {formatDateTime(review.createdAt)}
            {review.edited ? ' · edited' : ''}
          </p>
        </div>
      </div>

      <div className="mt-3 flex items-center gap-2">
        <Stars rating={review.rating} />
        <span aria-hidden="true" className="rounded-full bg-amber-50 px-1.5 py-0.5 text-[11px] font-bold text-amber-700 tabular-nums ring-1 ring-amber-200 ring-inset">
          {review.rating}/5
        </span>
      </div>
      <h3 id={titleId} className="mt-1.5 text-base font-semibold break-words text-slate-900">
        {heading}
      </h3>
      {review.comment && <p className="mt-1 text-sm leading-relaxed break-words whitespace-pre-line text-slate-700">{review.comment}</p>}

      <div className="mt-3 flex flex-wrap items-center gap-2 text-xs">
        <Chip icon="tag">
          {reviewTargetLabel(review.targetType)}
          {review.targetName ? `: ${review.targetName}` : ''}
        </Chip>
        {review.orderNumber && <Chip icon="receipt">Order {review.orderNumber}</Chip>}
        {moderation && review.storeName && <Chip icon="store">{review.storeName}</Chip>}
      </div>

      {moderation && flag && review.status === 'FLAGGED' && (
        <p className="mt-3 flex items-start gap-1.5 rounded-xl bg-amber-50 px-3 py-2 text-xs font-medium text-amber-800 ring-1 ring-amber-200 ring-inset">
          <Icon name="starFlag" className="size-4" />
          <span className="min-w-0">Flagged automatically: {flag}</span>
        </p>
      )}
      {moderation && review.moderationReason && review.status !== 'FLAGGED' && (
        <p className="mt-3 flex items-start gap-1.5 rounded-xl bg-slate-50 px-3 py-2 text-xs text-slate-600 ring-1 ring-slate-200 ring-inset">
          <Icon name="note" className="size-4" />
          <span className="min-w-0 break-words">Moderation note: “{review.moderationReason}”</span>
        </p>
      )}
      {review.reply && (
        <div className="mt-3 rounded-xl border-l-4 border-emerald-400 bg-gradient-to-r from-emerald-50 to-teal-50/40 px-3.5 py-2.5 text-sm">
          <p className="flex items-center gap-1.5 text-xs font-semibold text-emerald-800">
            <Icon name="chat" className="size-4" />
            Store reply · {formatDateTime(review.reply.updatedAt ?? review.reply.repliedAt)}
          </p>
          <p className="mt-1 break-words whitespace-pre-line text-slate-700">{review.reply.text}</p>
        </div>
      )}
      {/* mt-auto keeps the buttons at the bottom when cards in a row have different heights. */}
      {actions && <div className="mt-auto flex flex-wrap gap-2 pt-4">{actions}</div>}
      {notice}
    </article>
  )
}
