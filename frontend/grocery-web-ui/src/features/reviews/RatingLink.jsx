import { cn } from '../../shared/utils/cn'
import { formatRating, ratingCountLabel } from './labels'
import { Stars } from './StarRating'

/** Id of the "Ratings & reviews" section on product and restaurant pages. */
export const REVIEWS_ANCHOR = 'reviews'

/**
 * @typedef {object} RatingLinkProps
 * @property {number | null | undefined} rating
 * @property {number | null | undefined} count
 * @property {string} [className]
 */

/**
 * "★★★★☆ 4.3 · 120 ratings" under a product's or restaurant's name, jumping to its reviews; nothing when unrated.
 * @param {RatingLinkProps} props
 */
export function RatingLink({ rating, count, className }) {
  if (!count || count <= 0 || rating === null || rating === undefined || !Number.isFinite(rating)) return null
  return (
    <a
      href={`#${REVIEWS_ANCHOR}`}
      className={cn(
        'mt-2 inline-flex items-center gap-2 rounded text-sm text-slate-600 hover:text-slate-900 hover:underline',
        className,
      )}
    >
      <Stars value={rating} />
      <span>
        <span className="font-semibold text-slate-900 tabular-nums">{formatRating(rating)}</span> ·{' '}
        {ratingCountLabel(count)}
      </span>
    </a>
  )
}
