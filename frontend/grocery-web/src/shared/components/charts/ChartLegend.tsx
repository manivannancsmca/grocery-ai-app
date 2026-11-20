import type { ReactNode } from 'react'
import { cn } from '../../utils/cn'

export interface LegendItem {
  key: string
  label: string
  color: string
  /** The figure printed after the name (donut legends: value and share). */
  value?: ReactNode
}

interface ChartLegendProps {
  items: readonly LegendItem[]
  /** Mirrors the mark: a short line for line charts, a square for bars, areas and segments. */
  shape?: 'line' | 'square'
  /** One item per row with its value at the end (donuts), or a wrapping row of names (trend charts). */
  layout?: 'row' | 'list'
  /** Highlights one item (the hovered segment). */
  activeKey?: string | null
  onActivate?: (key: string | null) => void
  className?: string
}

/** Names the series by colour, so identity never rests on colour-matching alone; the text stays in text colours. */
export function ChartLegend({ items, shape = 'square', layout = 'row', activeKey, onActivate, className }: ChartLegendProps) {
  return (
    <ul className={cn(layout === 'row' ? 'mb-3 flex flex-wrap gap-x-4 gap-y-1.5' : 'space-y-1', 'text-xs', className)}>
      {items.map((item) => (
        <li
          key={item.key}
          onMouseEnter={() => onActivate?.(item.key)}
          onMouseLeave={() => onActivate?.(null)}
          className={cn(
            'flex items-center gap-2 text-slate-600',
            layout === 'list' && 'rounded-lg px-2 py-1.5 transition-colors',
            layout === 'list' && activeKey === item.key && 'bg-slate-50',
            activeKey !== undefined && activeKey !== null && activeKey !== item.key && 'opacity-55',
          )}
        >
          <span
            aria-hidden="true"
            className={cn('shrink-0', shape === 'line' ? 'h-0.5 w-3.5 rounded-full' : 'size-2.5 rounded-[3px]')}
            style={{ backgroundColor: item.color }}
          />
          <span className="min-w-0 flex-1 truncate">{item.label}</span>
          {item.value !== undefined && <span className="shrink-0 font-semibold text-slate-900 tabular-nums">{item.value}</span>}
        </li>
      ))}
    </ul>
  )
}
