import { cn } from '../../utils/cn'

/** @typedef {import('react').ReactNode} ReactNode */

/** @typedef {'gray' | 'green' | 'amber' | 'red' | 'blue' | 'violet'} BadgeTone */

/** @type {Record<BadgeTone, string>} */
const toneClasses = {
  gray: 'bg-slate-100 text-slate-700 ring-slate-200',
  green: 'bg-gradient-to-r from-emerald-50 to-teal-50 text-emerald-700 ring-emerald-200',
  amber: 'bg-gradient-to-r from-amber-50 to-orange-50 text-amber-800 ring-amber-200',
  red: 'bg-gradient-to-r from-red-50 to-rose-50 text-red-700 ring-red-200',
  blue: 'bg-gradient-to-r from-sky-50 to-blue-50 text-sky-700 ring-sky-200',
  violet: 'bg-gradient-to-r from-violet-50 to-fuchsia-50 text-violet-700 ring-violet-200',
}

/**
 * @typedef {object} BadgeProps
 * @property {BadgeTone} [tone]
 * @property {ReactNode} children
 * @property {string} [className]
 * @property {boolean} [dot] Shows a small status dot before the text.
 */

/** @param {BadgeProps} props */
export function Badge({ tone = 'gray', children, className, dot }) {
  return (
    <span
      className={cn(
        'inline-flex items-center gap-1.5 rounded-full px-2 py-0.5 text-xs font-medium whitespace-nowrap ring-1 ring-inset',
        toneClasses[tone],
        className,
      )}
    >
      {dot && <span className="size-1.5 rounded-full bg-current" aria-hidden="true" />}
      {children}
    </span>
  )
}
