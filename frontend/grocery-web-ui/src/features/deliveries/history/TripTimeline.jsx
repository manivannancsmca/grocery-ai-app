import { useId } from 'react'
import { Skeleton } from '../../../shared/components/ui'
import { Icon } from '../../../shared/icons'
import { cn } from '../../../shared/utils/cn'
import { formatDistanceKm } from '../../../shared/utils/format'
import { groupTripsByDay, tripsText } from './historyTrips'
import { TripRow } from './TripRow'

/** @typedef {import('../api').DeliverySummary} DeliverySummary */
/** @typedef {import('./historyTrips').TripDay} TripDay */

/**
 * One day: a header (Today / Yesterday / the date, how many trips and km) over its trips.
 * @param {{ day: TripDay }} props
 */
function DayPanel({ day }) {
  const headingId = useId()
  const km = day.km > 0 ? formatDistanceKm(day.km) : null
  const isToday = day.title === 'Today'
  return (
    <li className="animate-fade-up overflow-hidden rounded-2xl border border-slate-200/80 bg-white shadow-sm shadow-slate-900/[0.04] motion-reduce:animate-none">
      <div className="flex items-center justify-between gap-3 border-b border-slate-100 bg-gradient-to-r from-emerald-50/80 via-white to-white px-4 py-2.5">
        <h3 id={headingId} className="flex min-w-0 flex-wrap items-baseline gap-x-2 text-sm font-bold text-slate-900">
          <Icon name={isToday ? 'clock' : 'calendar'} className="size-4 self-center" />
          {day.title}
          {day.subtitle && <span className="text-xs font-medium text-slate-500">{day.subtitle}</span>}
        </h3>
        <p className="shrink-0 text-xs font-medium text-slate-500 tabular-nums">
          {tripsText(day.trips.length)}
          {km && ` · ${km}`}
        </p>
      </div>
      <ul aria-labelledby={headingId} className="divide-y divide-slate-100">
        {day.trips.map((trip) => (
          <TripRow key={trip.deliveryId} trip={trip} />
        ))}
      </ul>
    </li>
  )
}

/**
 * One page of finished trips, newest first, grouped by India-time day.
 * @param {{ trips: readonly DeliverySummary[], dimmed?: boolean }} props
 */
export function TripTimeline({ trips, dimmed }) {
  return (
    <ol aria-label="Your deliveries" className={cn('space-y-4 transition-opacity', dimmed && 'opacity-60')}>
      {groupTripsByDay(trips).map((day) => (
        <DayPanel key={day.day} day={day} />
      ))}
    </ol>
  )
}

/** The shape of a day of trips while the first page loads. */
export function TripTimelineSkeleton() {
  return (
    <div
      role="status"
      aria-label="Loading your deliveries"
      className="overflow-hidden rounded-2xl border border-slate-200/80 bg-white shadow-sm"
    >
      <div className="border-b border-slate-100 px-4 py-3">
        <Skeleton className="h-4 w-32" />
      </div>
      {Array.from({ length: 3 }, (_, index) => (
        <div key={index} className="flex items-center gap-3 border-b border-slate-100 px-4 py-4 last:border-b-0">
          <Skeleton className="size-9 rounded-xl" />
          <div className="flex-1 space-y-2">
            <Skeleton className="h-4 w-2/5" />
            <Skeleton className="h-3 w-1/4" />
          </div>
          <Skeleton className="hidden h-5 w-20 rounded-full sm:block" />
        </div>
      ))}
    </div>
  )
}
