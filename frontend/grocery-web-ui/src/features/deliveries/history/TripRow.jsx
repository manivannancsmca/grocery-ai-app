import { Badge } from '../../../shared/components/ui'
import { Icon } from '../../../shared/icons'
import { cn } from '../../../shared/utils/cn'
import { formatDateTime, formatDistanceKm, formatTime } from '../../../shared/utils/format'
import { historyStoreName } from '../labels'
import { formatMinutes } from '../statsPeriod'
import { rideMinutes, tripOutcome } from './historyTrips'

/** @typedef {import('react').ReactNode} ReactNode */
/** @typedef {import('../../../shared/icons').IconName} IconName */
/** @typedef {import('../api').DeliverySummary} DeliverySummary */

/**
 * A time of day; the full date and time on hover.
 * @param {{ value: string }} props
 */
function At({ value }) {
  return (
    <time dateTime={value} title={formatDateTime(value)} className="tabular-nums">
      {formatTime(value)}
    </time>
  )
}

/**
 * @typedef {object} StopProps
 * @property {IconName} icon
 * @property {string} tileClassName Background of the picture tile.
 * @property {string} name
 * @property {string} [className] Width on wide rows.
 * @property {ReactNode} children
 */

/**
 * One end of the trip: a picture, the place and what happened there.
 * @param {StopProps} props
 */
function Stop({ icon, tileClassName, name, className, children }) {
  return (
    <div className={cn('flex min-w-0 items-center gap-3', className)}>
      <span
        aria-hidden="true"
        className={cn(
          'flex size-9 shrink-0 items-center justify-center rounded-xl bg-gradient-to-br ring-1',
          tileClassName,
        )}
      >
        <Icon name={icon} className="size-5" />
      </span>
      <div className="min-w-0">
        {/* Phones: up to two lines; wide rows: one line, the full name on hover. */}
        <p title={name} className="line-clamp-2 text-sm font-semibold break-words text-slate-900 @3xl:line-clamp-1">
          {name}
        </p>
        <p className="text-xs text-slate-500">{children}</p>
      </div>
    </div>
  )
}

/**
 * The ride between the two stops: a dotted line with the distance and ride time. Phones: the line runs down between
 * the two pictures; wide rows: it stretches across from the store to the drop-off and ends in an arrow.
 * @param {{ km: number | null, minutes: number | null }} props
 */
function Leg({ km, minutes }) {
  const parts = [formatDistanceKm(km), minutes === null ? null : formatMinutes(minutes)].filter(Boolean)
  return (
    <div className="relative flex min-h-6 items-center gap-3 @3xl:min-w-36 @3xl:flex-1 @3xl:justify-center">
      <span
        aria-hidden="true"
        className="ml-[17px] h-6 border-l-2 border-dotted border-slate-300 @3xl:absolute @3xl:inset-x-1 @3xl:top-1/2 @3xl:ml-0 @3xl:h-0 @3xl:border-t-2 @3xl:border-l-0"
      />
      <Icon
        name="chevronRight"
        className="absolute top-1/2 -right-0.5 hidden size-3.5 -translate-y-1/2 text-slate-400 @3xl:block"
      />
      {parts.length > 0 && (
        <span className="relative inline-flex items-center gap-1 rounded-full bg-slate-50 px-2 py-0.5 text-xs font-semibold whitespace-nowrap text-slate-600 ring-1 ring-slate-200 ring-inset">
          <Icon name="route" className="size-3.5" />
          <span className="sr-only">Trip: </span>
          {parts.join(' · ')}
        </span>
      )}
    </div>
  )
}

/**
 * One finished trip: store → customer area with the distance and ride time between them, when it was picked up and
 * handed over (or closed as not delivered), the order number and the outcome. A container query switches it from a
 * stacked card (phones, narrow columns) to a single row (wide lists).
 * @param {{ trip: DeliverySummary }} props
 */
export function TripRow({ trip }) {
  const outcome = tripOutcome(trip.status)
  const store = trip.store ? historyStoreName(trip) : 'Store'
  return (
    <li
      className={cn(
        '@container transition-colors hover:bg-slate-50/80',
        !outcome.delivered && 'bg-rose-50/40 hover:bg-rose-50/70',
      )}
    >
      <div className="flex flex-col gap-2.5 px-4 py-3.5 @3xl:flex-row @3xl:items-center @3xl:gap-4">
        <div className="flex items-center justify-between gap-3 @3xl:order-last @3xl:w-36 @3xl:shrink-0 @3xl:flex-col @3xl:items-end @3xl:gap-1.5">
          <Badge tone={outcome.tone} dot>
            {outcome.label}
          </Badge>
          <span className="font-mono text-xs text-slate-500" title="Order number">
            {trip.orderNumber}
          </span>
        </div>

        <div className="flex min-w-0 flex-1 flex-col gap-1 @3xl:flex-row @3xl:items-center @3xl:gap-3">
          <Stop
            icon="store"
            tileClassName="from-amber-50 to-orange-50 ring-amber-100"
            name={store}
            className="@3xl:max-w-[34%]"
          >
            {trip.pickedUpAt ? (
              <>
                Picked up <At value={trip.pickedUpAt} />
              </>
            ) : (
              'Pickup time not recorded'
            )}
          </Stop>
          <Leg km={trip.tripKm} minutes={rideMinutes(trip)} />
          <Stop
            icon={outcome.icon}
            tileClassName={
              outcome.delivered ? 'from-emerald-50 to-teal-50 ring-emerald-100' : 'from-rose-50 to-red-50 ring-rose-100'
            }
            name={trip.dropArea ?? 'Customer'}
            className="@3xl:w-[38%] @3xl:shrink-0"
          >
            <span className={cn(!outcome.delivered && 'font-medium text-rose-700')}>
              {outcome.label}
              {trip.finishedAt && (
                <>
                  {outcome.delivered ? ' ' : ' · '}
                  <At value={trip.finishedAt} />
                </>
              )}
            </span>
          </Stop>
        </div>
      </div>
    </li>
  )
}
