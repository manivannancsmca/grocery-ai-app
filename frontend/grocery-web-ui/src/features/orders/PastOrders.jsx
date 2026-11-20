/** My orders → past orders, grouped by day. */

import { useId } from 'react'
import { Link } from 'react-router'
import { Icon } from '../../shared/icons'
import { cn } from '../../shared/utils/cn'
import { formatPrice } from '../../shared/utils/format'
import { ordersText, showsPaymentStatus } from './ordersListView'
import { OrderStatusPill, PaymentStatusPill, StoreTile } from './OrderVisuals'
import { ItemsLine, PlacedAt } from './OrderListParts'
import { orderDescription, orderPath } from './orderListHelpers'

/** @typedef {import('./ordersListView').OrderDay} OrderDay */
/** @typedef {import('./api').OrderSummary} OrderSummary */

/**
 * A finished order as one dense row. Phones: store and total, the items, then the status. Wider lists: the time and
 * order number join the store; at full width it is a single line with aligned columns (store, items, status, total).
 * @param {{ order: OrderSummary }} props
 */
function PastOrderRow({ order }) {
  const detailsId = useId()
  const time = <PlacedAt value={order.createdAt} />
  return (
    <li>
      <Link
        to={orderPath(order)}
        aria-label={`Order ${order.orderNumber} from ${order.store.name}`}
        aria-describedby={detailsId}
        className={cn(
          'group flex items-start gap-3 px-3.5 py-3 transition-colors hover:bg-emerald-50/50 focus-visible:-outline-offset-2 @md:px-4 @xl:items-center @4xl:gap-4',
          'focus-visible:outline-2 focus-visible:outline-emerald-600',
        )}
      >
        <span id={detailsId} className="sr-only">
          {orderDescription(order)}
        </span>
        <StoreTile type={order.type} size="sm" />
        <div className="grid min-w-0 flex-1 grid-cols-[minmax(0,1fr)_auto] items-center gap-x-3 gap-y-1 @4xl:grid-cols-[minmax(0,1.15fr)_minmax(0,1fr)_16.5rem_5.5rem] @4xl:gap-x-5">
          <div className="min-w-0">
            <p className="truncate text-sm font-semibold text-slate-900 group-hover:text-emerald-800">
              {order.store.name}
            </p>
            <p className="mt-0.5 hidden min-w-0 items-center gap-1.5 text-xs text-slate-500 @xl:flex">
              {time}
              <span aria-hidden="true">·</span>
              <span className="truncate font-mono">{order.orderNumber}</span>
            </p>
          </div>
          <p className="text-right text-sm font-bold text-slate-900 tabular-nums @4xl:order-last @4xl:text-base">
            {formatPrice(order.total)}
          </p>
          <ItemsLine
            order={order}
            className="col-span-2 @xl:col-span-1"
            lead={
              <>
                <span className="shrink-0 text-slate-500 @xl:hidden">
                  {time}
                  <span aria-hidden="true"> ·</span>
                </span>{' '}
              </>
            }
          />
          <div className="col-span-2 flex flex-wrap items-center gap-1.5 @xl:col-span-1 @xl:justify-end @4xl:justify-start">
            <OrderStatusPill status={order.status} />
            {showsPaymentStatus(order) && <PaymentStatusPill status={order.payment.status} />}
          </div>
        </div>
        <Icon
          name="chevronRight"
          className="size-4 shrink-0 self-center text-slate-400 transition-transform group-hover:translate-x-0.5 group-hover:text-emerald-600 motion-reduce:transition-none"
        />
      </Link>
    </li>
  )
}

/**
 * One day: a header (Today / Yesterday / the date and how many orders) over its rows.
 * @param {{ day: OrderDay<OrderSummary> }} props
 */
export function DayPanel({ day }) {
  const headingId = useId()
  return (
    <li className="overflow-hidden rounded-2xl border border-slate-200/80 bg-white shadow-sm shadow-slate-900/[0.04]">
      <div className="flex items-center justify-between gap-3 border-b border-slate-100 bg-gradient-to-r from-emerald-50/80 via-white to-white px-4 py-2.5">
        <h3 id={headingId} className="flex min-w-0 flex-wrap items-baseline gap-x-2 text-sm font-bold text-slate-900">
          <Icon name={day.title === 'Today' ? 'clock' : 'calendar'} className="size-4 self-center" />
          {day.title}
          {day.subtitle && <span className="text-xs font-medium text-slate-500">{day.subtitle}</span>}
        </h3>
        <p className="shrink-0 text-xs font-medium text-slate-500 tabular-nums">{ordersText(day.orders.length)}</p>
      </div>
      <ul aria-labelledby={headingId} className="@container divide-y divide-slate-100">
        {day.orders.map((order) => (
          <PastOrderRow key={order.id} order={order} />
        ))}
      </ul>
    </li>
  )
}
