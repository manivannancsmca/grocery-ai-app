import { useId, useState } from 'react'
import { ApiError } from '../../shared/api/problemDetail'
import { ErrorState, Skeleton, TextField } from '../../shared/components/ui'
import { ACCENT_CLASSES } from '../../shared/components/ui/statAccents'
import { useSearchParamsUpdater } from '../../shared/hooks/useSearchParamsUpdater'
import { Icon } from '../../shared/icons'
import { cn } from '../../shared/utils/cn'
import { formatCount, formatMoney, formatPercent } from '../../shared/utils/format'
import { indiaDay } from '../../shared/utils/indiaDate'
import { usePartnerStats } from './hooks'
import { customRangeError, formatMinutes, isStatsPeriod, presetRange, STATS_PERIODS } from './statsPeriod'
import { rangeText } from './performanceFormat'
import { Charts } from './PerformanceCharts'

/** @typedef {import('../../shared/icons').IconName} IconName */
/** @typedef {import('./api').PartnerStatsView} PartnerStatsView */
/** @typedef {import('react').ReactNode} ReactNode */
/** @typedef {import('../../shared/components/ui/statAccents').StatAccent} StatAccent */
/** @typedef {import('./statsPeriod').StatsPeriod} StatsPeriod */
/** @typedef {import('./api').StatsRange} StatsRange */

/**
 * Today / This week / This month / Custom, kept in the URL (\`?period=&from=&to=\`) so a refresh keeps it.
 * @returns {{ period: StatsPeriod, range: StatsRange | null, from: string, to: string, error: string | null, set: (next: Record<string, string | null>) => void }}
 */
function usePeriod() {
  const [params, update] = useSearchParamsUpdater()
  const raw = params.get('period')
  const period = isStatsPeriod(raw) ? raw : 'week'
  const today = indiaDay()
  if (period !== 'custom') {
    const range = presetRange(period, today)
    return {
      period,
      range,
      from: range.from,
      to: range.to,
      error: null,
      set: (next) => update(next, { replace: true }),
    }
  }
  const from = params.get('from') ?? presetRange('week', today).from
  const to = params.get('to') ?? today
  const error = customRangeError(from, to)
  return { period, range: error ? null : { from, to }, from, to, error, set: (next) => update(next, { replace: true }) }
}

/**
 * True when nothing happened in the range (a new partner, a day off).
 * @param {PartnerStatsView} stats
 * @returns {boolean}
 */
function isQuiet(stats) {
  const t = stats.totals
  return t.assigned + t.delivered + t.failed + t.cancelled + t.offersReceived + t.onlineMinutes === 0
}

/**
 * @typedef {object} KpiProps
 * @property {string} label
 * @property {ReactNode} value
 * @property {ReactNode} hint
 * @property {IconName} icon
 * @property {StatAccent} accent
 */

/**
 * A compact headline number in the look of a `StatCard` (colour bar on top, soft glow), with the picture beside the
 * label so six fit in one row on a desktop. A `<div>` with `<dt>` / `<dd>`: lives in a `<dl>`.
 * @param {KpiProps} props
 */
function Kpi({ label, value, hint, icon, accent }) {
  const colours = ACCENT_CLASSES[accent]
  return (
    <div className="relative isolate min-w-0 overflow-hidden rounded-2xl border border-slate-200/70 bg-white px-3 pt-3.5 pb-3 @xl:px-3.5 shadow-sm shadow-slate-900/[0.04]">
      <span aria-hidden="true" className={cn('absolute inset-x-0 top-0 h-1 bg-gradient-to-r', colours.bar)} />
      <span
        aria-hidden="true"
        className={cn('absolute -top-8 -right-8 -z-10 size-20 rounded-full blur-2xl', colours.glow)}
      />
      <dt className="flex min-w-0 items-center gap-2 text-xs leading-tight font-medium text-slate-500">
        <span
          aria-hidden="true"
          className={cn(
            'flex size-7 shrink-0 items-center justify-center rounded-lg bg-gradient-to-br ring-1',
            colours.tile,
          )}
        >
          <Icon name={icon} className="size-4" />
        </span>
        <span className="line-clamp-2 min-w-0">{label}</span>
      </dt>
      <dd className="mt-2 truncate text-xl leading-tight font-bold tracking-tight text-slate-900 tabular-nums">
        {value}
      </dd>
      <dd className="mt-0.5 truncate text-xs text-slate-400">{hint}</dd>
    </div>
  )
}

/**
 * Today / This week / This month / Custom as a segmented control (the app's tab look; pressed buttons, kept in the URL).
 * @param {{ period: StatsPeriod, onPick: (period: StatsPeriod) => void }} props
 */
function PeriodPicker({ period, onPick }) {
  return (
    <div
      role="group"
      aria-label="Period"
      className="flex w-full gap-1 rounded-2xl bg-slate-100/80 p-1 ring-1 ring-slate-200/60 ring-inset sm:w-auto"
    >
      {STATS_PERIODS.map((option) => (
        <button
          key={option.value}
          type="button"
          aria-pressed={period === option.value}
          onClick={() => onPick(option.value)}
          className={cn(
            'grow rounded-xl px-2.5 py-1.5 text-sm font-semibold whitespace-nowrap transition-all duration-200 sm:grow-0 sm:px-3.5',
            'focus-visible:outline-2 focus-visible:outline-offset-1 focus-visible:outline-emerald-600',
            period === option.value
              ? 'bg-white text-emerald-700 shadow-sm ring-1 shadow-slate-900/10 ring-slate-200/80'
              : 'text-slate-500 hover:bg-white/60 hover:text-slate-800',
          )}
        >
          {option.label}
        </button>
      ))}
    </div>
  )
}

/**
 * The partner's own performance on their dashboard for the chosen period (today, this week, this month or custom
 * days, India time) from \`GET /api/delivery/stats\`: six headline numbers in one strip, then the charts behind them —
 * deliveries by outcome and day, estimated earnings, offers and how they were answered, delivery times, success rate
 * and online time. On phones and small tablets the charts wait behind "More insights", so the page stays short.
 */
export function PartnerPerformance() {
  const { period, range, from, to, error, set } = usePeriod()
  // A custom range with a mistake keeps showing the last valid one (the query is simply not changed).
  const stats = usePartnerStats(range ?? presetRange('week'))
  const data = stats.data
  const today = indiaDay()
  const [insightsOpen, setInsightsOpen] = useState(false)
  const chartsId = useId()

  function pick(next) {
    set({ period: next === 'week' ? null : next, ...(next === 'custom' ? { from, to } : { from: null, to: null }) })
  }

  return (
    <section aria-labelledby="performance-title" className="@container space-y-3">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <div className="flex min-w-0 items-center gap-3">
          <span
            aria-hidden="true"
            className="flex size-9 shrink-0 items-center justify-center rounded-xl bg-white shadow-sm ring-1 ring-emerald-100"
          >
            <Icon name="chart" className="size-5" />
          </span>
          <div className="min-w-0">
            <h2 id="performance-title" className="text-lg leading-tight font-bold text-slate-900">
              Your performance
            </h2>
            <p className="text-xs text-slate-500 sm:text-sm">
              {data ? rangeText(data.from, data.to) : 'Your deliveries, earnings and time online'} · India time
            </p>
          </div>
        </div>
        <PeriodPicker period={period} onPick={pick} />
      </div>

      {period === 'custom' && (
        <div className="flex flex-wrap items-end gap-3 rounded-2xl border border-slate-200/80 bg-white p-3 shadow-sm">
          <TextField
            label="From"
            type="date"
            value={from}
            max={today}
            containerClassName="w-40"
            onChange={(event) => set({ period: 'custom', from: event.target.value, to })}
          />
          <TextField
            label="To"
            type="date"
            value={to}
            max={today}
            containerClassName="w-40"
            onChange={(event) => set({ period: 'custom', from, to: event.target.value })}
          />
          {error && (
            <p role="alert" className="pb-2 text-sm font-medium text-red-700">
              {error}
            </p>
          )}
        </div>
      )}

      {stats.isPending ? (
        <div
          role="status"
          aria-label="Loading your performance"
          className="grid grid-cols-2 gap-2.5 @xl:grid-cols-3 @4xl:grid-cols-6"
        >
          {Array.from({ length: 6 }, (_, index) => (
            <Skeleton key={index} className="h-24 rounded-2xl" />
          ))}
        </div>
      ) : stats.isError && !data ? (
        <ErrorState
          title="Could not load your performance"
          message={stats.error.message}
          correlationId={stats.error instanceof ApiError ? stats.error.correlationId : null}
          onRetry={() => void stats.refetch()}
        />
      ) : data ? (
        <div
          className={cn('space-y-3 transition-opacity', stats.isFetching && stats.isPlaceholderData && 'opacity-60')}
        >
          <dl aria-label="Headline numbers" className="grid grid-cols-2 gap-2.5 @xl:grid-cols-3 @4xl:grid-cols-6">
            <Kpi
              label="Estimated earnings"
              value={formatMoney(data.totals.earnings, data.payRate.currency)}
              hint="Delivered orders"
              icon="savings"
              accent="emerald"
            />
            <Kpi
              label="Delivered"
              value={formatCount(data.totals.delivered)}
              hint={`of ${formatCount(data.totals.assigned)} assigned`}
              icon="checkCircle"
              accent="teal"
            />
            <Kpi
              label="Success rate"
              value={data.totals.successRate === null ? '—' : formatPercent(data.totals.successRate)}
              hint={`${formatCount(data.totals.failed)} not delivered`}
              icon="trendUp"
              accent="sky"
            />
            <Kpi
              label="Avg delivery time"
              value={formatMinutes(data.totals.avgTotalMinutes)}
              hint="Assigned → delivered"
              icon="clock"
              accent="violet"
            />
            <Kpi
              label="Distance"
              value={`${data.totals.distanceKm.toFixed(1)} km`}
              hint="Trips delivered"
              icon="route"
              accent="amber"
            />
            <Kpi
              label="Online time"
              value={formatMinutes(data.totals.onlineMinutes)}
              hint="Sharing your location"
              icon="pulse"
              accent="rose"
            />
          </dl>
          <p className="flex flex-wrap items-center gap-x-4 gap-y-1 text-xs text-slate-600 sm:text-sm">
            <span className="inline-flex items-center gap-1.5">
              <span aria-hidden="true" className="size-2 rounded-full bg-sky-500" />
              In progress now:{' '}
              <strong className="font-semibold text-slate-900">{formatCount(data.totals.inProgress)}</strong>
            </span>
            <span className="inline-flex items-center gap-1.5">
              <span aria-hidden="true" className="size-2 rounded-full bg-amber-500" />
              Offers waiting:{' '}
              <strong className="font-semibold text-slate-900">{formatCount(data.totals.offersPending)}</strong>
            </span>
            <span className="inline-flex items-center gap-1.5">
              <span aria-hidden="true" className="size-2 rounded-full bg-slate-400" />
              Cancelled after assignment:{' '}
              <strong className="font-semibold text-slate-900">{formatCount(data.totals.cancelled)}</strong>
            </span>
          </p>
          {isQuiet(data) ? (
            <div className="flex items-center gap-3 rounded-2xl border border-dashed border-slate-300 bg-white/80 px-4 py-4">
              <Icon name="chart" className="size-8" />
              <div className="min-w-0">
                <p className="text-sm font-semibold text-slate-900">No activity in this period yet</p>
                <p className="text-sm text-slate-600">
                  Go online and accept a delivery: your charts fill in as you work.
                </p>
              </div>
            </div>
          ) : (
            <>
              {/* Phones and small tablets: the charts are folded away behind one button; from `lg` they always show. */}
              <button
                type="button"
                aria-expanded={insightsOpen}
                aria-controls={chartsId}
                onClick={() => setInsightsOpen((open) => !open)}
                className="flex w-full items-center gap-3 rounded-2xl border border-slate-200/80 bg-white px-4 py-3 text-left shadow-sm transition-colors hover:border-emerald-300 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-emerald-600 lg:hidden"
              >
                <span
                  aria-hidden="true"
                  className="flex size-8 shrink-0 items-center justify-center rounded-lg bg-emerald-50 ring-1 ring-emerald-100"
                >
                  <Icon name="chart" className="size-4.5" />
                </span>
                <span className="min-w-0 flex-1">
                  <span className="block text-sm font-semibold text-slate-900">More insights</span>
                  <span className="block text-xs text-slate-500">Deliveries, earnings, offers and time by day</span>
                </span>
                <Icon
                  name="chevronRight"
                  className={cn(
                    'size-5 text-slate-500 transition-transform',
                    insightsOpen ? '-rotate-90' : 'rotate-90',
                  )}
                />
              </button>
              <div id={chartsId} className={cn(!insightsOpen && 'max-lg:hidden')}>
                <Charts stats={data} />
              </div>
            </>
          )}
          <p className="text-xs text-slate-500">
            Earnings are an estimate: {formatMoney(data.payRate.basePerDelivery, data.payRate.currency)} per delivered
            order plus {formatMoney(data.payRate.perKm, data.payRate.currency)} per km of trip distance (straight line,
            store to customer). Online time is counted while the app shares your location.
          </p>
        </div>
      ) : null}
    </section>
  )
}
