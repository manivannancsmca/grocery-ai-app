import { Skeleton, StatCard } from '../../../shared/components/ui'
import { Icon } from '../../../shared/icons'
import { cn } from '../../../shared/utils/cn'
import { formatCount, formatDistanceKm, formatPercent } from '../../../shared/utils/format'
import { indiaDay } from '../../../shared/utils/indiaDate'
import { useDeliveryHistorySummary, usePartnerStats } from '../hooks'
import { lastSevenDays, successTone, tripsText } from './historyTrips'

/** @typedef {import('react').ReactNode} ReactNode */

const RING_COLOURS = {
  good: 'stroke-emerald-500',
  warning: 'stroke-amber-500',
  critical: 'stroke-rose-500',
  none: 'stroke-transparent',
}

/**
 * A number, a shimmer while it loads, or a dash when it could not be read.
 * @param {number | undefined} value
 * @param {boolean} loading
 * @returns {ReactNode}
 */
function shown(value, loading) {
  if (value !== undefined) return formatCount(value)
  return loading ? <Skeleton className="mt-1 h-6 w-10" /> : '—'
}

/**
 * `3.4 km ridden`, or null while the distance is unknown or zero.
 * @param {number | undefined} km
 * @returns {string | null}
 */
function ridden(km) {
  const text = km ? formatDistanceKm(km) : null
  return text ? `${text} ridden` : null
}

/**
 * Under "Not delivered": point at the red trips when there are some; praise only once something was delivered.
 * @param {number | undefined} failed
 * @param {number | undefined} delivered
 * @returns {string | undefined}
 */
function notDeliveredHint(failed, delivered) {
  if (failed === undefined) return undefined
  if (failed > 0) return 'Marked in red in your trips'
  return delivered ? 'All handed over' : 'None so far'
}

/**
 * A small progress ring (decorative: the percentage is written next to it).
 * @param {{ ratio: number | null }} props
 */
function SuccessRing({ ratio }) {
  const tone = successTone(ratio)
  // r = 15.915 makes the circle 100 units around, so the dash length is the percentage.
  const percent = ratio === null ? 0 : Math.round(Math.max(0, Math.min(1, ratio)) * 100)
  return (
    <svg aria-hidden="true" viewBox="0 0 36 36" className="row-span-2 mt-0.5 mr-3 size-10 -rotate-90">
      <circle cx="18" cy="18" r="15.915" fill="none" strokeWidth="4" className="stroke-slate-100" />
      <circle
        cx="18"
        cy="18"
        r="15.915"
        fill="none"
        strokeWidth="4"
        strokeLinecap="round"
        strokeDasharray={`${percent} 100`}
        className={cn('transition-[stroke-dasharray] duration-700', RING_COLOURS[tone])}
      />
    </svg>
  )
}

/**
 * Same look as a `StatCard`, with a ring instead of a picture. A `<div>` with `<dt>` / `<dd>`: lives in a `<dl>`.
 * @param {{ delivered: number | undefined, failed: number | undefined, loading: boolean }} props
 */
function SuccessRateTile({ delivered, failed, loading }) {
  const finished = delivered === undefined || failed === undefined ? undefined : delivered + failed
  const ratio = finished ? (delivered ?? 0) / finished : null
  let hint = 'No finished trips yet'
  if (finished === undefined) hint = loading ? 'Counting your trips…' : 'Could not be read'
  else if (finished > 0) hint = `${formatCount(delivered)} of ${tripsText(finished)} delivered`

  return (
    <div className="group/stat relative isolate grid grid-cols-[auto_minmax(0,1fr)] content-start overflow-hidden rounded-2xl border border-slate-200/70 bg-white px-4 py-3.5 shadow-sm shadow-slate-900/[0.04] transition-all duration-300 hover:-translate-y-0.5 hover:shadow-lg hover:shadow-slate-900/[0.07]">
      <span aria-hidden="true" className="absolute inset-x-0 top-0 h-1 bg-gradient-to-r from-teal-400 to-cyan-500" />
      <span
        aria-hidden="true"
        className="absolute -top-8 -right-8 -z-10 size-24 rounded-full bg-teal-200/50 blur-2xl transition-transform duration-500 group-hover/stat:scale-125"
      />
      <SuccessRing ratio={ratio} />
      <dt className="col-start-2 row-start-2 min-w-0 text-xs font-medium text-slate-500">Success rate this week</dt>
      <dd className="col-start-2 row-start-1 min-w-0 text-xl font-bold tracking-tight text-slate-900 sm:text-2xl">
        {ratio !== null ? (
          formatPercent(ratio)
        ) : finished === undefined && loading ? (
          <Skeleton className="mt-1 h-6 w-12" />
        ) : (
          '—'
        )}
      </dd>
      <dd className="col-start-2 row-start-3 mt-1 min-w-0 text-xs text-slate-400">{hint}</dd>
    </div>
  )
}

/**
 * The partner's numbers above the history: delivered today, delivered and not delivered in the last 7 days, and the
 * success rate of those 7 days as a ring (`/delivery/history/summary`). The distance ridden comes from
 * `/delivery/stats` over the same 7 days; if that call fails the tiles simply leave it out. They always cover today and
 * the last 7 days: the trip list's filters do not change them, and the caption says so.
 */
export function HistoryOverview() {
  const today = indiaDay()
  const summary = useDeliveryHistorySummary()
  const stats = usePartnerStats(lastSevenDays(today))
  const counts = summary.data
  const loading = summary.isPending
  const kmToday = stats.data?.days.find((day) => day.date === today)?.distanceKm
  const failed = counts?.failedThisWeek

  return (
    <section aria-label="Delivery summary">
      <dl className="grid grid-cols-2 gap-3 lg:grid-cols-4">
        <StatCard
          icon="homeCheck"
          accent="emerald"
          label="Delivered today"
          value={shown(counts?.deliveredToday, loading)}
          hint={ridden(kmToday) ?? (counts?.deliveredToday === 0 ? 'Nothing yet today' : undefined)}
        />
        <StatCard
          icon="scooter"
          accent="sky"
          label="Delivered this week"
          value={shown(counts?.deliveredThisWeek, loading)}
          hint={ridden(stats.data?.totals.distanceKm) ?? undefined}
        />
        <StatCard
          icon="homeCross"
          accent="rose"
          label="Not delivered this week"
          value={shown(failed, loading)}
          valueClassName={failed ? 'text-rose-700' : 'text-slate-900'}
          hint={notDeliveredHint(failed, counts?.deliveredThisWeek)}
        />
        <SuccessRateTile delivered={counts?.deliveredThisWeek} failed={failed} loading={loading} />
      </dl>
      <p className="mt-2 flex items-center gap-1.5 text-xs text-slate-500">
        <Icon name="calendar" className="size-3.5 shrink-0" />
        Today and the last 7 days, India time · the filters below don’t change these numbers
      </p>
    </section>
  )
}
