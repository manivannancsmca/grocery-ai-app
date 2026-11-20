import { useState } from 'react'
import { Spinner } from '../../shared/components/ui'
import { Icon } from '../../shared/icons'
import { cn } from '../../shared/utils/cn'
import { formatPrice, formatTime } from '../../shared/utils/format'
import { closedAt } from './hooks'
import { closedOrderSummary } from './labels'

/** @typedef {import('../../shared/icons').IconName} IconName */
/** @typedef {import('./api').StoreKind} StoreKind */
/** @typedef {import('./hooks').useClosedTodayStoreOrders} useClosedTodayStoreOrders */
/** @typedef {import('./labels').ClosedTone} ClosedTone */

/** Rows listed before "Show all": the board stays the focus of the page. */
const ROWS_SHOWN = 6

/** @type {Record<ClosedTone, { icon: IconName, chip: string }>} */
const TONES = {
  done: { icon: 'checkCircle', chip: 'bg-emerald-50 text-emerald-800 ring-emerald-200' },
  rejected: { icon: 'xCircle', chip: 'bg-red-50 text-red-700 ring-red-200' },
  cancelled: { icon: 'ban', chip: 'bg-slate-100 text-slate-700 ring-slate-200' },
}

/**
 * Today's picked-up, rejected and cancelled orders, newest first: one tidy row each, with a status chip and the reason.
 * @param {{ kind: StoreKind, closed: ReturnType<typeof useClosedTodayStoreOrders> }} props
 */
export function ClosedToday({ kind, closed }) {
  const [showAll, setShowAll] = useState(false)
  const orders = closed.data ?? []
  const rows = showAll ? orders : orders.slice(0, ROWS_SHOWN)
  return (
    <section
      aria-label="Closed today"
      className="overflow-hidden rounded-xl border border-slate-200/70 bg-white shadow-sm shadow-slate-900/[0.03]"
    >
      <div className="flex items-center gap-2 border-b border-slate-100 px-4 py-3">
        <h2 className="flex items-center gap-2 text-sm font-semibold text-slate-900">
          <Icon name="checkAll" className="size-4 text-slate-500" />
          <span>
            Closed today
            {orders.length > 0 && <span className="sr-only">:</span>}
          </span>{' '}
          {orders.length > 0 && (
            <span className="rounded-full bg-slate-100 px-2 py-0.5 text-xs font-bold text-slate-600 tabular-nums">
              {orders.length}
            </span>
          )}
        </h2>
        <p className="ml-auto hidden text-xs text-slate-500 sm:block">Picked up, rejected and cancelled</p>
      </div>
      {closed.isPending ? (
        <div className="px-4 py-4">
          <Spinner size="sm" label="Loading closed orders" />
        </div>
      ) : closed.isError ? (
        <p className="px-4 py-4 text-sm text-red-700">Could not load today’s closed orders: {closed.error.message}</p>
      ) : orders.length === 0 ? (
        <p className="px-4 py-4 text-sm text-slate-500">Nothing picked up, rejected or cancelled today.</p>
      ) : (
        <>
          <ul className="divide-y divide-slate-100">
            {rows.map((order) => {
              const summary = closedOrderSummary(kind, order)
              const tone = TONES[summary.tone]
              return (
                <li
                  key={order.orderId}
                  className="grid grid-cols-[minmax(0,1fr)_auto] items-center gap-x-3 gap-y-1 px-4 py-2.5 text-sm sm:grid-cols-[8.5rem_auto_minmax(0,1fr)_auto_4.5rem]"
                >
                  <span className="flex min-w-0 items-center gap-2 sm:contents">
                    <span className="font-semibold text-slate-900 tabular-nums">{order.orderNumber}</span>
                    <span
                      className={cn(
                        'inline-flex w-fit items-center gap-1 rounded-full px-2 py-0.5 text-xs font-semibold whitespace-nowrap ring-1 ring-inset',
                        tone.chip,
                      )}
                    >
                      <Icon name={tone.icon} className="size-3.5" />
                      {summary.chip}
                    </span>
                  </span>
                  <time
                    dateTime={closedAt(order)}
                    className="text-right text-xs whitespace-nowrap text-slate-500 tabular-nums sm:order-last"
                  >
                    {formatTime(closedAt(order))}
                  </time>
                  <span className="min-w-0 text-[13px] text-slate-600">
                    {summary.reason}
                    {summary.note && (
                      <span className="text-slate-500 italic">
                        {summary.reason ? ' · ' : ''}“{summary.note}”
                      </span>
                    )}
                  </span>
                  <span className="text-right text-xs whitespace-nowrap text-slate-500 tabular-nums">
                    {order.itemCount} item{order.itemCount === 1 ? '' : 's'} ·{' '}
                    {formatPrice(order.subtotal, order.currency)}
                  </span>
                </li>
              )
            })}
          </ul>
          {orders.length > ROWS_SHOWN && (
            <div className="border-t border-slate-100 px-4 py-2">
              <button
                type="button"
                aria-expanded={showAll}
                onClick={() => setShowAll((all) => !all)}
                className="rounded-md text-sm font-semibold text-emerald-700 hover:text-emerald-800 hover:underline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-emerald-600"
              >
                {showAll ? 'Show fewer' : `Show all ${orders.length}`}
              </button>
            </div>
          )}
        </>
      )}
    </section>
  )
}
