import { EmptyBoxIllustration } from '../../illustrations'
import { cn } from '../../utils/cn'

/** @typedef {import('react').ReactNode} ReactNode */

/**
 * @typedef {object} EmptyStateProps
 * @property {string} title
 * @property {string} [description]
 * @property {ReactNode} [action]
 * @property {ReactNode} [illustration] Scene above the title (see `shared/illustrations`); an empty box by default.
 * @property {'md' | 'lg'} [size] `lg` for a page-level empty state (bigger art, more room); `md` inside cards and lists.
 * @property {string} [className]
 */

/** @param {EmptyStateProps} props */
export function EmptyState({ title, description, action, illustration, size = 'md', className }) {
  return (
    <div
      className={cn(
        'animate-fade-up flex flex-col items-center justify-center rounded-2xl border border-slate-200/80 bg-gradient-to-b from-white to-slate-50 text-center shadow-sm',
        size === 'lg' ? 'px-6 py-12' : 'px-6 py-9',
        className,
      )}
    >
      <div className={size === 'lg' ? 'w-44 sm:w-52' : 'w-32 sm:w-36'}>{illustration ?? <EmptyBoxIllustration />}</div>
      <h3 className={cn('mt-4 font-semibold text-slate-900', size === 'lg' ? 'text-lg' : 'text-base')}>{title}</h3>
      {description && <p className="mt-1.5 max-w-md text-sm leading-relaxed text-slate-500">{description}</p>}
      {action && <div className="mt-5 flex flex-wrap items-center justify-center gap-2">{action}</div>}
    </div>
  )
}
