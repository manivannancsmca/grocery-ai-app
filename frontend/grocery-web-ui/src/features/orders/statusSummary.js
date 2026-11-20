/** @typedef {import('../../shared/icons').IconName} IconName */
/** @typedef {import('./api').OrderTimelineEntry} OrderTimelineEntry */
/** @typedef {import('./api').OrderView} OrderView */

/**
 * The order page's one status line: the colour, icon and words of where the order is now (the details under it are
 * written by `OrderStatusHero`).
 */

/** @typedef {'info' | 'success' | 'warning' | 'danger' | 'neutral'} StatusTone */

/**
 * @typedef {object} OrderStatusLook
 * @property {StatusTone} tone
 * @property {IconName} icon
 * @property {string} title
 * @property {boolean} busy A spinner instead of the icon (stock is being checked).
 */

/** @typedef {Pick<OrderView, 'status' | 'type' | 'readyInMinutes' | 'rejection'>} LookInput */

/**
 * @param {LookInput} order
 * @returns {OrderStatusLook}
 */
export function orderStatusLook(order) {
  const grocery = order.type === 'GROCERY'
  const look = (tone, icon, title, busy = false) => ({ tone, icon, title, busy })
  switch (order.status) {
    case 'CREATED':
      return look('info', 'receipt', 'Checking stock…', true)
    case 'AWAITING_PAYMENT':
      return look('warning', 'card', 'Waiting for your payment')
    case 'CONFIRMED':
      return look('success', 'checkCircle', 'Order confirmed')
    case 'ACCEPTED':
      return look(
        'success',
        'store',
        order.readyInMinutes ? `Accepted — ready in about ${order.readyInMinutes} min` : 'Accepted',
      )
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
      const outOfStock =
        order.rejection?.reason === 'OUT_OF_STOCK' || (order.rejection?.unavailableItems.length ?? 0) > 0
      return look('danger', 'ban', outOfStock ? 'Some items are out of stock' : 'This order could not be accepted')
    }
    case 'CANCELLED':
      return look('neutral', 'xCircle', 'Order cancelled')
  }
}

/**
 * When the order was delivered: the delivery's time, else the DELIVERED step of the timeline.
 * @param {Pick<OrderView, 'delivery' | 'timeline'>} order
 * @returns {string | null}
 */
export function deliveredAtOf(order) {
  return (
    order.delivery?.deliveredAt ??
    [...order.timeline].reverse().find((entry) => entry.status === 'DELIVERED')?.at ??
    null
  )
}

/**
 * Local calendar day of a timestamp, to tell whether an update happened on the day the order was placed.
 * @param {string} value
 * @returns {string}
 */
function dayOf(value) {
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? value : `${date.getFullYear()}-${date.getMonth()}-${date.getDate()}`
}

/**
 * Whether an update should show its date as well as its time (it is not on the day the order was placed).
 * @param {Pick<OrderTimelineEntry, 'at'>} entry
 * @param {string} placedAt
 * @returns {boolean}
 */
export function updateNeedsDate(entry, placedAt) {
  return dayOf(entry.at) !== dayOf(placedAt)
}
