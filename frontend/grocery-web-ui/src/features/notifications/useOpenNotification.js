import { useNavigate } from 'react-router'
import { safeRedirectPath } from '../../shared/auth/redirect'
import { adminOrderLink } from '../admin-orders/labels'
import { useMarkNotificationRead } from './hooks'

/** @typedef {import('./api').NotificationView} NotificationView */

/** Store order notifications that belong on the order board. */
const BOARD_TYPES = new Set(['NEW_ORDER', 'ORDER_AUTO_REJECTED', 'ORDER_CANCELLED'])

/** Phase 12: the customer's review notifications (`/account/reviews`) and return decisions (the order's page). */
const CUSTOMER_REVIEW_TYPES = new Set(['REVIEW_NOT_PUBLISHED', 'REVIEW_REPLY'])
const CUSTOMER_RETURN_TYPES = new Set(['RETURN_APPROVED', 'RETURN_REJECTED'])

/**
 * Where opening a notification goes. Phase 8 sends NEW_ORDER (and the store's other order notifications) with
 * the order board as link; ones stored before Phase 8 point at the store dashboard and are sent to the board too.
 * Phase 10: STUCK_ORDER links to `/admin/orders`; with the order's id in `data` it opens that order's drawer.
 * @param {Pick<NotificationView, 'type' | 'link'> & { data?: NotificationView['data'] }} notification
 * @returns {string | null}
 */
export function notificationTarget(notification) {
  const target = safeRedirectPath(notification.link)
  if (target && BOARD_TYPES.has(notification.type) && (target === '/vendor' || target === '/restaurant'))
    return `${target}/orders`
  if (target === '/admin/orders' && notification.type === 'STUCK_ORDER') {
    const orderId = notification.data?.orderId
    const orderNumber = notification.data?.orderNumber
    if (typeof orderId === 'string' && orderId) {
      return adminOrderLink({ orderId, orderNumber: typeof orderNumber === 'string' ? orderNumber : null })
    }
  }
  // Phase 12: a customer notification sent without a link still opens the right page.
  if (!target && CUSTOMER_REVIEW_TYPES.has(notification.type)) return '/account/reviews'
  if (!target && CUSTOMER_RETURN_TYPES.has(notification.type)) {
    const orderId = notification.data?.orderId
    return typeof orderId === 'string' && orderId ? `/orders/${encodeURIComponent(orderId)}` : '/account/returns'
  }
  return target
}

/**
 * Opening a notification marks it read (optimistically, in the background) and follows its `link` — an
 * in-app path only; anything else (null, external URL) just marks it read.
 * @param {() => void} [onOpened]
 * @returns {(notification: NotificationView) => void}
 */
export function useOpenNotification(onOpened) {
  const navigate = useNavigate()
  const markRead = useMarkNotificationRead()

  return (notification) => {
    if (!notification.read) markRead.mutate(notification)
    onOpened?.()
    const target = notificationTarget(notification)
    if (target) void navigate(target)
  }
}
