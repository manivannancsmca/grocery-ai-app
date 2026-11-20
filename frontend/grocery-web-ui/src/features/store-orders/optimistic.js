/** @typedef {import('./api').StoreOrderView} StoreOrderView */

/**
 * A board action as the optimistic update sees it (rejecting goes through its dialog and is not shown early).
 * @typedef {(
 *   | { type: 'accept', readyInMinutes: number }
 *   | { type: 'preparing' }
 *   | { type: 'ready' }
 *   | { type: 'reject' }
 * )} OptimisticAction
 */

/**
 * What the card looks like once the server agrees: shown at once, so the card moves to its next column on the click
 * instead of after the round trip. `null` = no early change (rejecting). The server's answer replaces it; a failure
 * restores the board as it was.
 * @param {StoreOrderView} order
 * @param {OptimisticAction} action
 * @param {number} now
 * @returns {StoreOrderView | null}
 */
export function optimisticOrder(order, action, now) {
  const at = new Date(now).toISOString()
  switch (action.type) {
    case 'accept':
      return {
        ...order,
        status: 'ACCEPTED',
        acceptedAt: at,
        readyInMinutes: action.readyInMinutes,
        readyBy: new Date(now + action.readyInMinutes * 60_000).toISOString(),
        timeline: [...order.timeline, { status: 'ACCEPTED', at }],
      }
    case 'preparing':
      return {
        ...order,
        status: 'PREPARING',
        preparingAt: at,
        timeline: [...order.timeline, { status: 'PREPARING', at }],
      }
    case 'ready':
      return {
        ...order,
        status: 'READY_FOR_PICKUP',
        readyAt: at,
        timeline: [...order.timeline, { status: 'READY_FOR_PICKUP', at }],
      }
    case 'reject':
      return null
  }
}
