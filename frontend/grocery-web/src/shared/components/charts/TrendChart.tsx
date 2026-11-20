import { type KeyboardEvent, type PointerEvent, type ReactNode, useId, useRef, useState } from 'react'
import { cn } from '../../utils/cn'
import { BRAND_COLOR, CHART_INK, seriesColor } from './chartTheme'
import { ChartLegend } from './ChartLegend'
import { compactNumber, labelStep, niceTicks } from './scale'
import { useElementWidth } from './useElementWidth'

export interface TrendSeries {
  key: string
  name: string
  /** `null` where there is no value (the line breaks there; no column). */
  values: readonly (number | null)[]
  /** Defaults to the brand colour for one series, the categorical slots in order for several. */
  color?: string
}

interface TrendChartProps {
  title: string
  /** A line under the title (a note, the highest value). */
  subtitle?: ReactNode
  /** One per data position, e.g. `YYYY-MM-DD` days. */
  categories: readonly string[]
  formatCategory?: (category: string) => string
  series: readonly TrendSeries[]
  kind?: 'line' | 'area' | 'column'
  /** Values in the tooltip and the keyboard readout. */
  format: (value: number) => string
  /** Y-axis ticks; compact numbers (1.2K, 3.4L) by default. */
  formatTick?: (value: number) => string
  height?: number
  className?: string
}

const TOP = 14
const RIGHT = 14
const BOTTOM = 28

/** Runs of consecutive non-null points, so a line breaks over a gap instead of dropping to zero. */
function runs(values: readonly (number | null)[]): Array<Array<[index: number, value: number]>> {
  const result: Array<Array<[number, number]>> = []
  let current: Array<[number, number]> = []
  values.forEach((value, index) => {
    if (value === null) {
      if (current.length) result.push(current)
      current = []
    } else current.push([index, value])
  })
  if (current.length) result.push(current)
  return result
}

/** A column with a 4px rounded top, square on the baseline. */
function columnPath(x: number, y: number, width: number, baseline: number): string {
  const r = Math.min(4, width / 2, baseline - y)
  return `M${x} ${baseline}V${y + r}Q${x} ${y} ${x + r} ${y}H${x + width - r}Q${x + width} ${y} ${x + width} ${y + r}V${baseline}Z`
}

/**
 * Values over a sequence of days (or any ordered categories) as a line, an area or columns. One y-axis from zero,
 * hairline grid, a crosshair and tooltip listing every series on hover, and the same readout with the arrow keys.
 */
export function TrendChart({
  title,
  subtitle,
  categories,
  formatCategory = (category) => category,
  series,
  kind = 'line',
  format,
  formatTick = compactNumber,
  height = 220,
  className,
}: TrendChartProps) {
  const containerRef = useRef<HTMLDivElement>(null)
  const width = useElementWidth(containerRef)
  const [active, setActive] = useState<number | null>(null)
  const titleId = useId()
  const hintId = useId()
  const gradientId = useId()

  const count = categories.length
  const colors = series.map((item, index) => item.color ?? (series.length === 1 ? BRAND_COLOR : seriesColor(index)))
  const ticks = niceTicks(Math.max(0, ...series.flatMap((item) => item.values.map((value) => value ?? 0))))
  const top = ticks.at(-1) ?? 1
  const tickLabels = ticks.map(formatTick)
  const left = Math.max(...tickLabels.map((label) => label.length)) * 6.6 + 14
  const plotWidth = Math.max(1, width - left - RIGHT)
  const plotHeight = Math.max(1, height - TOP - BOTTOM)
  const baseline = TOP + plotHeight
  const band = plotWidth / Math.max(count, 1)
  const y = (value: number) => baseline - (Math.max(0, value) / top) * plotHeight
  const x = (index: number) =>
    kind === 'column' ? left + band * (index + 0.5) : left + (count <= 1 ? plotWidth / 2 : (index * plotWidth) / (count - 1))
  const step = labelStep(count, plotWidth)
  const barWidth = Math.max(2, Math.min(24, (band * 0.72) / Math.max(series.length, 1) - 2))
  const groupWidth = series.length * barWidth + (series.length - 1) * 2

  function indexAt(event: PointerEvent<SVGRectElement>): number | null {
    const box = event.currentTarget.ownerSVGElement?.getBoundingClientRect()
    if (!box || box.width === 0 || count === 0) return null
    const px = ((event.clientX - box.left) * width) / box.width - left
    const index = kind === 'column' ? Math.floor(px / band) : Math.round(count <= 1 ? 0 : (px * (count - 1)) / plotWidth)
    return Math.max(0, Math.min(count - 1, index))
  }

  function onKeyDown(event: KeyboardEvent<HTMLDivElement>) {
    const last = count - 1
    const current = active ?? last
    const next = { ArrowLeft: current - 1, ArrowRight: current + 1, Home: 0, End: last }[event.key]
    if (next === undefined) return
    event.preventDefault()
    setActive(Math.max(0, Math.min(last, next)))
  }

  const show = (value: number | null | undefined) => (value === null || value === undefined ? '—' : format(value))
  const readout =
    active === null
      ? ''
      : `${formatCategory(categories[active] ?? '')}: ${series.map((item) => `${series.length > 1 ? `${item.name} ` : ''}${show(item.values[active])}`).join(', ')}`
  const tooltipLeft = active === null ? 0 : Math.max(70, Math.min(width - 70, x(active)))

  return (
    <figure aria-labelledby={titleId} className={cn('min-w-0', className)}>
      <figcaption className="mb-3 flex flex-wrap items-baseline justify-between gap-x-3 gap-y-1">
        <span id={titleId} className="text-sm font-semibold text-slate-800">
          {title}
        </span>
        {subtitle && <span className="text-xs text-slate-500">{subtitle}</span>}
      </figcaption>
      {series.length > 1 && <ChartLegend items={series.map((item, index) => ({ key: item.key, label: item.name, color: colors[index] ?? BRAND_COLOR }))} shape={kind === 'line' ? 'line' : 'square'} />}
      <div
        ref={containerRef}
        tabIndex={0}
        role="group"
        aria-labelledby={titleId}
        aria-describedby={hintId}
        onKeyDown={onKeyDown}
        onFocus={() => setActive((current) => current ?? count - 1)}
        onBlur={() => setActive(null)}
        className="relative rounded-xl outline-none focus-visible:ring-2 focus-visible:ring-emerald-500/40"
      >
        <span id={hintId} className="sr-only">
          Use the left and right arrow keys to read each value.
        </span>
        <span aria-live="polite" className="sr-only">
          {readout}
        </span>
        <svg width={width} height={height} viewBox={`0 0 ${width} ${height}`} className="block w-full overflow-visible" aria-hidden="true">
          <defs>
            <linearGradient id={gradientId} x1="0" x2="0" y1="0" y2="1">
              <stop offset="0" stopColor={colors[0]} stopOpacity="0.22" />
              <stop offset="1" stopColor={colors[0]} stopOpacity="0.02" />
            </linearGradient>
          </defs>
          {ticks.map((tick, index) => (
            <g key={tick}>
              <line x1={left} x2={left + plotWidth} y1={y(tick)} y2={y(tick)} stroke={index === 0 ? CHART_INK.axis : CHART_INK.grid} strokeWidth="1" />
              <text x={left - 8} y={y(tick)} dy="0.32em" textAnchor="end" fontSize="11" fill={CHART_INK.muted} className="tabular-nums">
                {tickLabels[index]}
              </text>
            </g>
          ))}
          {categories.map((category, index) =>
            index % step === 0 ? (
              <text key={category} x={x(index)} y={height - 8} textAnchor="middle" fontSize="11" fill={CHART_INK.muted}>
                {formatCategory(category)}
              </text>
            ) : null,
          )}

          {kind === 'column'
            ? series.map((item, seriesIndex) =>
                item.values.map((value, index) =>
                  value !== null && value > 0 ? (
                    <path
                      key={`${item.key}-${categories[index]}`}
                      d={columnPath(x(index) - groupWidth / 2 + seriesIndex * (barWidth + 2), y(value), barWidth, baseline)}
                      fill={colors[seriesIndex]}
                      opacity={active === null || active === index ? 1 : 0.45}
                      className="chart-grow-y transition-opacity duration-200"
                      style={{ animationDelay: `${Math.min(index * 18, 400)}ms` }}
                    />
                  ) : null,
                ),
              )
            : series.map((item, seriesIndex) => {
                const color = colors[seriesIndex]
                const segments = runs(item.values)
                const end = segments.at(-1)?.at(-1)
                return (
                  <g key={item.key}>
                    {segments.map((segment) => {
                      const line = `M${segment.map(([index, value]) => `${x(index)} ${y(value)}`).join('L')}`
                      const first = segment[0]?.[0] ?? 0
                      const last = segment.at(-1)?.[0] ?? 0
                      return (
                        <g key={first}>
                          {kind === 'area' && (
                            <path
                              d={`${line}L${x(last)} ${baseline}L${x(first)} ${baseline}Z`}
                              fill={seriesIndex === 0 ? `url(#${gradientId})` : color}
                              opacity={seriesIndex === 0 ? undefined : 0.1}
                              className="animate-fade-in"
                            />
                          )}
                          <path d={line} pathLength={1} fill="none" stroke={color} strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" className="chart-draw" />
                        </g>
                      )
                    })}
                    {end && <circle cx={x(end[0])} cy={y(end[1])} r="4" fill={color} stroke={CHART_INK.surface} strokeWidth="2" />}
                  </g>
                )
              })}

          {active !== null && kind !== 'column' && (
            <g>
              <line x1={x(active)} x2={x(active)} y1={TOP} y2={baseline} stroke={CHART_INK.axis} strokeWidth="1" />
              {series.map((item, seriesIndex) => {
                const value = item.values[active]
                return value === null || value === undefined ? null : (
                  <circle key={item.key} cx={x(active)} cy={y(value)} r="5" fill={colors[seriesIndex]} stroke={CHART_INK.surface} strokeWidth="2" />
                )
              })}
            </g>
          )}
          {/* The hover layer: the whole plot, so the pointer only has to be near a day. */}
          <rect
            x={left}
            y={TOP}
            width={plotWidth}
            height={plotHeight}
            fill="transparent"
            onPointerMove={(event) => setActive(indexAt(event))}
            onPointerLeave={() => setActive(null)}
          />
        </svg>
        {active !== null && (
          <div
            aria-hidden="true"
            className="animate-fade-in pointer-events-none absolute top-0 z-10 min-w-32 -translate-x-1/2 rounded-xl border border-slate-200/80 bg-white/95 px-3 py-2 text-xs shadow-lg shadow-slate-900/10 backdrop-blur"
            style={{ left: tooltipLeft }}
          >
            <p className="mb-1 font-medium text-slate-500">{formatCategory(categories[active] ?? '')}</p>
            {series.map((item, seriesIndex) => (
              <p key={item.key} className="flex items-center gap-2 whitespace-nowrap">
                <span className="h-0.5 w-3 rounded-full" style={{ backgroundColor: colors[seriesIndex] }} />
                <span className="font-semibold text-slate-900 tabular-nums">{show(item.values[active])}</span>
                {series.length > 1 && <span className="text-slate-500">{item.name}</span>}
              </p>
            ))}
          </div>
        )}
      </div>
    </figure>
  )
}
