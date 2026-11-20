import type { ReactNode } from 'react'
import { Icon } from '../../icons'
import { cn } from '../../utils/cn'
import { Button } from './Button'

interface FilterBarProps {
  /** The page's own search and filter fields (TextField, SelectField…), laid out by the page. */
  children: ReactNode
  /** How many filters are set: shown as a count, and "Clear all" appears when above zero. */
  activeCount?: number
  /** Resets every filter (the page decides what that means, e.g. removing them from the URL). */
  onClear?: () => void
  /** Small text on the right of the title (e.g. "Dates are India time"). */
  note?: ReactNode
  title?: string
  className?: string
}

/**
 * The card around a list page's search and filters: a title with how many filters are on, "Clear all", and the
 * page's fields. Only presentation: the page keeps its own filter state.
 */
export function FilterBar({ children, activeCount = 0, onClear, note, title = 'Search and filters', className }: FilterBarProps) {
  return (
    <section
      aria-label={title}
      className={cn(
        'animate-fade-up motion-reduce:animate-none rounded-2xl border border-slate-200/80 bg-gradient-to-br from-white via-white to-emerald-50/50 p-4 shadow-sm shadow-slate-900/[0.04]',
        className,
      )}
    >
      <div className="mb-3 flex flex-wrap items-center justify-between gap-2">
        <p className="flex items-center gap-2 text-sm font-semibold text-slate-800">
          <span aria-hidden="true" className="flex size-7 items-center justify-center rounded-lg bg-gradient-to-br from-emerald-50 to-teal-50 ring-1 ring-emerald-100">
            <Icon name="filter" className="size-4" />
          </span>
          {title}
          {activeCount > 0 && (
            <span className="rounded-full bg-emerald-600 px-2 py-0.5 text-[11px] font-bold text-white tabular-nums">
              {activeCount} active
            </span>
          )}
        </p>
        <div className="flex items-center gap-3">
          {note && <p className="text-xs text-slate-500">{note}</p>}
          {activeCount > 0 && onClear && (
            <Button variant="ghost" size="sm" onClick={onClear}>
              <Icon name="close" className="size-3.5" />
              Clear all
            </Button>
          )}
        </div>
      </div>
      {children}
    </section>
  )
}
