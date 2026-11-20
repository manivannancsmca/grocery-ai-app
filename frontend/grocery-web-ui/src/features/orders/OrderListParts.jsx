/** Pieces shown on every order in the list: the items line and the time it was placed. */

import { cn } from '../../shared/utils/cn'
import { formatDateTime, formatTime } from '../../shared/utils/format'
import { itemsText } from './ordersListView'

/** @typedef {import('./api').OrderSummary} OrderSummary */
/** @typedef {import('react').ReactNode} ReactNode */

/**
 * The first item and how many in all: the name gives way first, the count always stays.
 * @param {{ order: OrderSummary, lead?: ReactNode, className?: string }} props
 */
export function ItemsLine({ order, lead, className }) {
  return (
    <p className={cn('flex min-w-0 items-baseline gap-1 text-sm text-slate-600', className)}>
      {lead}
      <span className="truncate text-slate-700">{order.firstItemName}</span>{' '}
      <span className="shrink-0 whitespace-nowrap text-slate-500 tabular-nums">· {itemsText(order.itemCount)}</span>
    </p>
  )
}

/**
 * A time of day; the full date and time on hover.
 * @param {{ value: string }} props
 */
export function PlacedAt({ value }) {
  return (
    <time dateTime={value} title={formatDateTime(value)} className="tabular-nums">
      {formatTime(value)}
    </time>
  )
}
