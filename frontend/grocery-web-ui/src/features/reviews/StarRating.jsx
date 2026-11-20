import { useId, useState } from 'react'
import { cn } from '../../shared/utils/cn'
import { formatRating, RATING_WORDS, ratingCountLabel, starsLabel } from './labels'

const STAR_PATH = 'M10 1.6 12.6 6.9l5.8.8-4.2 4.1 1 5.8L10 14.9l-5.2 2.7 1-5.8L1.6 7.7l5.8-.8L10 1.6Z'

const STAR_SIZES = { xs: 'size-3.5', sm: 'size-4', md: 'size-5', lg: 'size-8' }
/** @typedef {keyof typeof STAR_SIZES} StarSize */

/** @param {{ className?: string }} props */
function StarShape({ className }) {
  return (
    <svg className={cn('shrink-0', className)} viewBox="0 0 20 20" fill="currentColor" aria-hidden="true">
      <path d={STAR_PATH} />
    </svg>
  )
}

/** @param {{ size: StarSize, className: string }} props */
function StarRow({ size, className }) {
  return (
    <span className={cn('flex gap-0.5', className)}>
      {[1, 2, 3, 4, 5].map((star) => (
        <StarShape key={star} className={STAR_SIZES[size]} />
      ))}
    </span>
  )
}

/**
 * @typedef {object} StarsProps
 * @property {number} value 0–5, fractions allowed (an average).
 * @property {StarSize} [size]
 * @property {string} [className]
 */

/**
 * Five stars filled up to `value`, read as "4.3 out of 5 stars".
 * @param {StarsProps} props
 */
export function Stars({ value, size = 'sm', className }) {
  const clamped = Math.max(0, Math.min(5, Number.isFinite(value) ? value : 0))
  const label = Number.isInteger(clamped) ? `${clamped} out of 5 stars` : `${formatRating(clamped)} out of 5 stars`
  return (
    <span role="img" aria-label={label} className={cn('relative inline-flex shrink-0', className)}>
      <StarRow size={size} className="text-slate-200" />
      <span className="absolute inset-y-0 left-0 overflow-hidden" style={{ width: `${(clamped / 5) * 100}%` }}>
        <StarRow size={size} className="text-amber-400" />
      </span>
    </span>
  )
}

/**
 * @typedef {object} RatingBadgeProps
 * @property {number | null | undefined} rating
 * @property {number | null | undefined} count
 * @property {string} [className]
 */

/**
 * "★ 4.3 (120)" on product and restaurant cards; nothing while a target has no ratings.
 * @param {RatingBadgeProps} props
 */
export function RatingBadge({ rating, count, className }) {
  if (!count || count <= 0 || rating === null || rating === undefined || !Number.isFinite(rating)) return null
  return (
    <p className={cn('flex items-center gap-1 text-xs', className)}>
      <StarShape className="size-3.5 text-amber-400" />
      <span aria-hidden="true" className="font-semibold text-slate-800 tabular-nums">
        {formatRating(rating)}
      </span>
      <span aria-hidden="true" className="text-slate-500 tabular-nums">
        ({count.toLocaleString('en-IN')})
      </span>
      <span className="sr-only">
        Rated {formatRating(rating)} out of 5 from {ratingCountLabel(count)}
      </span>
    </p>
  )
}

/**
 * @typedef {object} StarInputProps
 * @property {number | null} value null = not chosen yet.
 * @property {(rating: number) => void} onChange
 * @property {string} label The group's name, e.g. "Your rating for Basmati Rice".
 * @property {boolean} [hideLabel] Hide the legend visually (the surrounding card names the target).
 * @property {string} [error]
 * @property {boolean} [disabled]
 */

/**
 * 1–5 stars as a radio group: arrow keys move between stars, each star is named ("4 stars — Good"), and the
 * chosen word is shown next to them. Hovering previews a rating.
 * @param {StarInputProps} props
 */
export function StarInput({ value, onChange, label, hideLabel = false, error, disabled = false }) {
  const name = useId()
  const errorId = `${name}-error`
  const [hover, setHover] = useState(null)
  const shown = hover ?? value ?? 0

  return (
    <fieldset aria-describedby={error ? errorId : undefined} disabled={disabled} className="min-w-0">
      <legend className={cn('text-sm font-medium text-slate-700', hideLabel && 'sr-only')}>{label}</legend>
      <div
        className={cn('flex flex-wrap items-center gap-x-1 gap-y-2', !hideLabel && 'mt-2')}
        onMouseLeave={() => setHover(null)}
      >
        <span className="flex items-center">
          {[1, 2, 3, 4, 5].map((star) => (
            <label
              key={star}
              onMouseEnter={() => !disabled && setHover(star)}
              className={cn('group rounded-lg p-1', disabled ? 'cursor-not-allowed opacity-60' : 'cursor-pointer')}
            >
              <input
                type="radio"
                name={name}
                value={star}
                checked={value === star}
                onChange={() => onChange(star)}
                className="peer sr-only"
              />
              <span className="sr-only">
                {starsLabel(star)} — {RATING_WORDS[star]}
              </span>
              <StarShape
                className={cn(
                  'size-8 rounded-md transition duration-150 peer-focus-visible:outline-2 peer-focus-visible:outline-offset-2 peer-focus-visible:outline-emerald-600 sm:size-9',
                  !disabled && 'group-hover:scale-110 group-active:scale-95',
                  star <= shown ? 'text-amber-400 drop-shadow-[0_1px_1px_rgb(180_83_9/0.25)]' : 'text-slate-200',
                )}
              />
            </label>
          ))}
        </span>
        <span
          aria-hidden="true"
          className={cn(
            'ml-1 inline-flex h-7 min-w-20 items-center justify-center rounded-full px-3 text-sm font-semibold transition-colors',
            shown > 0 ? 'bg-amber-50 text-amber-800 ring-1 ring-amber-200 ring-inset' : 'text-slate-400',
          )}
        >
          {shown > 0 ? RATING_WORDS[shown] : 'Tap a star'}
        </span>
      </div>
      {error && (
        <p id={errorId} className="mt-1.5 text-xs font-medium text-red-600">
          {error}
        </p>
      )}
    </fieldset>
  )
}
