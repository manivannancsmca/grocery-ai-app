import { useId } from 'react'
import { cn } from '../../utils/cn'
import { BRAND_COLOR } from './chartTheme'

export interface BarListItem {
  key: string
  label: string
  /** A second, quieter line under the name (store, type). */
  detail?: string
  value: number
}

interface BarListProps {
  title: string
  items: readonly BarListItem[]
  format: (value: number) => string
  color?: string
  className?: string
}

/**
 * Ranked horizontal bars, largest first, one hue, on a shared zero baseline: the clearest way to compare a top-N.
 * The value sits at each bar's tip; the list is an ordered list, so it reads in rank order without the picture.
 */
export function BarList({ title, items, format, color = BRAND_COLOR, className }: BarListProps) {
  const titleId = useId()
  const max = Math.max(0, ...items.map((item) => item.value))
  return (
    <figure aria-labelledby={titleId} className={cn('min-w-0', className)}>
      <figcaption id={titleId} className="mb-3 text-sm font-semibold text-slate-800">
        {title}
      </figcaption>
      <ol className="space-y-2.5">
        {items.map((item, index) => (
          <li key={item.key} className="group/bar grid grid-cols-[minmax(0,9rem)_minmax(0,1fr)_auto] items-center gap-3 text-sm sm:grid-cols-[minmax(0,12rem)_minmax(0,1fr)_auto]">
            <span className="min-w-0">
              <span className="block truncate font-medium text-slate-800">{item.label}</span>
              {item.detail && <span className="block truncate text-xs text-slate-500">{item.detail}</span>}
            </span>
            <span aria-hidden="true" className="h-2.5 overflow-hidden rounded-full bg-slate-100">
              <span
                className="chart-grow-x block h-full rounded-full transition-[filter] duration-200 group-hover/bar:brightness-110"
                style={{ width: `${max > 0 ? Math.max(1.5, (item.value / max) * 100) : 0}%`, backgroundColor: color, animationDelay: `${index * 60}ms` }}
              />
            </span>
            <span className="text-right font-semibold text-slate-900 tabular-nums">{format(item.value)}</span>
          </li>
        ))}
      </ol>
    </figure>
  )
}
