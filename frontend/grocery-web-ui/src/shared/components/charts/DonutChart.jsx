import { useId, useState } from 'react'
import { cn } from '../../utils/cn'
import { CHART_INK, seriesColor } from './chartTheme'
import { ChartLegend } from './ChartLegend'

/** @typedef {import('react').ReactNode} ReactNode */

/**
 * @typedef {object} DonutPart
 * @property {string} key
 * @property {string} label
 * @property {number} value
 * @property {string} [color] Defaults to the categorical slot of the part's position (keep parts in a fixed order).
 */

/**
 * @typedef {object} DonutChartProps
 * @property {string} title
 * @property {readonly DonutPart[]} parts
 * @property {(value: number) => string} format
 * @property {string} [totalLabel] Under the total in the middle, e.g. "orders".
 * @property {'donut' | 'pie'} [variant] `pie` fills the middle (no total shown in it).
 * @property {ReactNode} [subtitle] A line under the title.
 * @property {string} [className]
 */

const SIZE = 168
const RADIUS = 62
const THICKNESS = 22
/** Surface gap between neighbouring segments, in hundredths of the circle. */
const GAP = 0.8

const percent = new Intl.NumberFormat('en-IN', { style: 'percent', maximumFractionDigits: 1 })

/**
 * One whole split into a few parts (keep it to six or fewer, in a fixed order). The legend lists every part with its
 * value and share, so nothing depends on reading the colours or hovering; hovering a part or its legend row lifts it
 * and shows it in the middle.
 * @param {DonutChartProps} props
 */
export function DonutChart({ title, parts, format, totalLabel, variant = 'donut', subtitle, className }) {
  const titleId = useId()
  const [activeKey, setActiveKey] = useState(null)
  const total = parts.reduce((sum, part) => sum + Math.max(0, part.value), 0)
  const colored = parts.map((part, index) => ({ ...part, color: part.color ?? seriesColor(index) }))
  const visible = colored.filter((part) => part.value > 0)
  const active = colored.find((part) => part.key === activeKey) ?? null
  const isPie = variant === 'pie'
  const radius = isPie ? RADIUS / 2 + THICKNESS / 4 : RADIUS
  const thickness = isPie ? RADIUS + THICKNESS / 2 : THICKNESS

  // Each segment starts where the previous ones end (shares in hundredths of the circle).
  const segments = visible.reduce((list, part) => {
    const start = list.at(-1)?.end ?? 0
    const share = total > 0 ? (part.value / total) * 100 : 0
    const length = visible.length > 1 ? Math.max(0.1, share - GAP) : share
    return [...list, { ...part, length, offset: -start, end: start + share }]
  }, [])

  return (
    <figure aria-labelledby={titleId} className={cn('min-w-0', className)}>
      <figcaption className="mb-3 flex flex-wrap items-baseline justify-between gap-x-3 gap-y-1">
        <span id={titleId} className="text-sm font-semibold text-slate-800">
          {title}
        </span>
        {subtitle && <span className="text-xs text-slate-500">{subtitle}</span>}
      </figcaption>
      <div className="flex flex-col items-center gap-5 sm:flex-row sm:items-center">
        <div className="relative shrink-0" style={{ width: SIZE, height: SIZE }}>
          <svg width={SIZE} height={SIZE} viewBox={`0 0 ${SIZE} ${SIZE}`} aria-hidden="true" className="-rotate-90">
            <circle
              cx={SIZE / 2}
              cy={SIZE / 2}
              r={radius}
              fill="none"
              stroke={CHART_INK.grid}
              strokeWidth={thickness}
              opacity="0.6"
            />
            {segments.map((segment, index) => (
              <circle
                key={segment.key}
                cx={SIZE / 2}
                cy={SIZE / 2}
                r={radius}
                fill="none"
                pathLength={100}
                stroke={segment.color}
                strokeWidth={activeKey === segment.key ? thickness + 6 : thickness}
                strokeDasharray={`${segment.length} ${100 - segment.length}`}
                strokeDashoffset={segment.offset}
                opacity={activeKey === null || activeKey === segment.key ? 1 : 0.4}
                onMouseEnter={() => setActiveKey(segment.key)}
                onMouseLeave={() => setActiveKey(null)}
                className="chart-sweep transition-[stroke-width,opacity] duration-200"
                style={{ animationDelay: `${index * 90}ms` }}
              />
            ))}
          </svg>
          {!isPie && (
            <div
              className="pointer-events-none absolute inset-0 flex flex-col items-center justify-center text-center"
              aria-hidden="true"
            >
              <span className="text-xl font-bold text-slate-900">{format(active ? active.value : total)}</span>
              <span className="max-w-24 truncate text-[11px] font-medium text-slate-500">
                {active ? active.label : (totalLabel ?? 'Total')}
              </span>
            </div>
          )}
        </div>
        <ChartLegend
          layout="list"
          className="w-full min-w-0 flex-1"
          activeKey={activeKey}
          onActivate={setActiveKey}
          items={colored.map((part) => ({
            key: part.key,
            label: part.label,
            color: part.color,
            value: (
              <>
                {format(part.value)}
                <span className="ml-1.5 font-normal text-slate-400">
                  {total > 0 ? percent.format(part.value / total) : '–'}
                </span>
              </>
            ),
          }))}
        />
      </div>
    </figure>
  )
}
