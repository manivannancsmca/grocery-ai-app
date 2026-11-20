import type { IconName } from '../../shared/icons'
import type { OrderTimelineEntry, OrderView } from './api'

/**
 * The order page's one status line: the colour, icon and words of where the order is now (the details under it are
 * written by `OrderStatusHero`).
 */

export type StatusTone = 'info' | 'success' | 'warning' | 'danger' | 'neutral'

export interface OrderStatusLook {
  tone: StatusTone
  icon: IconName
  title: string
  /** A spinner instead of the icon (stock is being checked). */
  busy: boolean
}

type LookInput = Pick<OrderView, 'status' | 'type' | 'readyInMinutes' | 'rejection'>

export function orderStatusLook(order: LookInput): OrderStatusLook {
  const grocery = order.type === 'GROCERY'
  const look = (tone: StatusTone, icon: IconName, title: string, busy = false): OrderStatusLook => ({ tone, icon, title, busy })
  switch (order.status) {
    case 'CREATED':
      return look('info', 'receipt', 'Checking stock…', true)
    case 'AWAITING_PAYMENT':
      return look('warning', 'card', 'Waiting for your payment')
    case 'CONFIRMED':
      return look('success', 'checkCircle', 'Order confirmed')
    case 'ACCEPTED':
      return look('success', 'store', order.readyInMinutes ? `Accepted — ready in about ${order.readyInMinutes} min` : 'Accepted')
    case 'PREPARING':
      return grocery ? look('info', 'box', 'Packing your order') : look('info', 'cooking', 'Preparing your food')
    case 'READY_FOR_PICKUP':
      return look('success', 'bag', 'Ready for pickup')
    case 'OUT_FOR_DELIVERY':
      return look('info', 'scooter', 'On the way')
    case 'DELIVERED':
      return look('success', 'homeCheck', 'Delivered')
    case 'DELIVERY_FAILED':
      return look('danger', 'warning', 'We could not deliver your order')
    case 'REJECTED': {
      const outOfStock = order.rejection?.reason === 'OUT_OF_STOCK' || (order.rejection?.unavailableItems.length ?? 0) > 0
      return look('danger', 'ban', outOfStock ? 'Some items are out of stock' : 'This order could not be accepted')
    }
    case 'CANCELLED':
      return look('neutral', 'xCircle', 'Order cancelled')
  }
}

/** When the order was delivered: the delivery's time, else the DELIVERED step of the timeline. */
export function deliveredAtOf(order: Pick<OrderView, 'delivery' | 'timeline'>): string | null {
  return order.delivery?.deliveredAt ?? [...order.timeline].reverse().find((entry) => entry.status === 'DELIVERED')?.at ?? null
}

/** Local calendar day of a timestamp, to tell whether an update happened on the day the order was placed. */
function dayOf(value: string): string {
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? value : `${date.getFullYear()}-${date.getMonth()}-${date.getDate()}`
}

/** Whether an update should show its date as well as its time (it is not on the day the order was placed). */
export function updateNeedsDate(entry: Pick<OrderTimelineEntry, 'at'>, placedAt: string): boolean {
  return dayOf(entry.at) !== dayOf(placedAt)
}
