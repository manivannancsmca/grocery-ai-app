import { cn } from '../../utils/cn'

/** @typedef {import('react').ReactNode} ReactNode */

/**
 * @typedef {object} LegendItem
 * @property {string} key
 * @property {string} label
 * @property {string} color
 * @property {ReactNode} [value] The figure printed after the name (donut legends: value and share).
 */

/**
 * @typedef {object} ChartLegendProps
 * @property {readonly LegendItem[]} items
 * @property {'line' | 'square'} [shape] Mirrors the mark: a short line for line charts, a square for bars, areas and segments.
 * @property {'row' | 'list'} [layout] One item per row with its value at the end (donuts), or a wrapping row of names (trend charts).
 * @property {string | null} [activeKey] Highlights one item (the hovered segment).
 * @property {(key: string | null) => void} [onActivate]
 * @property {string} [className]
 */

/**
 * Names the series by colour, so identity never rests on colour-matching alone; the text stays in text colours.
 * @param {ChartLegendProps} props
 */
export function ChartLegend({ items, shape = 'square', layout = 'row', activeKey, onActivate, className }) {
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
          {item.value !== undefined && (
            <span className="shrink-0 font-semibold text-slate-900 tabular-nums">{item.value}</span>
          )}
        </li>
      ))}
    </ul>
  )
}
