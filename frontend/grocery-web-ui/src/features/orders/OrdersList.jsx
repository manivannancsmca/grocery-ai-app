import { useId } from 'react'
import { Skeleton } from '../../shared/components/ui'
import { Icon } from '../../shared/icons'
import { cn } from '../../shared/utils/cn'
import { indiaDay } from '../../shared/utils/indiaDate'
import { groupOrdersByDay, splitActiveOrders } from './ordersListView'
import { ActiveOrders } from './ActiveOrders'
import { DayPanel } from './PastOrders'

/** @typedef {import('./api').OrderSummary} OrderSummary */

/**
 * @typedef {object} OrdersOverviewProps
 * @property {readonly OrderSummary[]} orders
 * @property {number} now
 * @property {boolean} live The live stream is open (the strip of orders under way says so).
 * @property {boolean} [dimmed] An older answer is on screen while the next one loads.
 */

/**
 * One page of the customer's orders: those under way first, then the finished ones by day.
 * @param {OrdersOverviewProps} props
 */
export function OrdersOverview({ orders, now, live, dimmed }) {
  const pastHeadingId = useId()
  const { active, past } = splitActiveOrders(orders)
  return (
    <div
      className={cn('animate-fade-up space-y-6 transition-opacity motion-reduce:animate-none', dimmed && 'opacity-60')}
    >
      {active.length > 0 && <ActiveOrders orders={active} now={now} live={live} />}
      {past.length > 0 && (
        <div className="space-y-3">
          {/* Only needed as a visible title when the strip above is there; screen readers always get it. */}
          <h2
            id={pastHeadingId}
            className={cn(
              'flex items-center gap-2 px-1 text-base font-bold text-slate-900',
              active.length === 0 && 'sr-only',
            )}
          >
            <Icon name="receipt" className="size-5" />
            Past orders
          </h2>
          <ol aria-label="Past orders" className="space-y-4">
            {groupOrdersByDay(past, indiaDay(now)).map((day) => (
              <DayPanel key={day.day} day={day} />
            ))}
          </ol>
        </div>
      )}
    </div>
  )
}

/** The shape of a day of orders while the first page loads. */
export function OrdersOverviewSkeleton() {
  return (
    <div
      role="status"
      aria-label="Loading your orders"
      className="overflow-hidden rounded-2xl border border-slate-200/80 bg-white shadow-sm"
    >
      <div className="flex items-center justify-between border-b border-slate-100 px-4 py-3">
        <Skeleton className="h-4 w-36" />
        <Skeleton className="h-3 w-16" />
      </div>
      {Array.from({ length: 5 }, (_, index) => (
        <div key={index} className="flex items-center gap-3 border-b border-slate-100 px-4 py-3.5 last:border-b-0">
          <Skeleton className="size-10 rounded-xl" />
          <div className="min-w-0 flex-1 space-y-2">
            <Skeleton className="h-4 w-2/5" />
            <Skeleton className="h-3 w-3/5 sm:w-1/3" />
          </div>
          <Skeleton className="hidden h-6 w-24 rounded-full sm:block" />
          <Skeleton className="h-4 w-12" />
        </div>
      ))}
    </div>
  )
}
