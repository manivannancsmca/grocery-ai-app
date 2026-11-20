import { NOTIFICATION_CATEGORIES, NOTIFICATION_SEVERITIES } from '../../shared/realtime/messages'

/** @typedef {import('../../shared/components/toast/toastContext').ToastTone} ToastTone */
/** @typedef {import('../../shared/realtime/messages').NotificationCategory} NotificationCategory */
/** @typedef {import('../../shared/realtime/messages').NotificationSeverity} NotificationSeverity */
/** @typedef {import('../../shared/realtime/messages').NotificationType} NotificationType */
/** @typedef {import('../../shared/realtime/messages').RealtimeStatus} RealtimeStatus */

/**
 * @param {string} value
 * @returns {NotificationSeverity}
 */
export function severityOf(value) {
  return NOTIFICATION_SEVERITIES.includes(value) ? value : 'INFO'
}

/**
 * @param {string} value
 * @returns {NotificationCategory | null}
 */
export function categoryOf(value) {
  return NOTIFICATION_CATEGORIES.includes(value) ? value : null
}

/**
 * Icon circle tints per severity (the app's Badge / Alert palette); the icon inside keeps its own colours.
 * @type {Record<NotificationSeverity, string>}
 */
export const SEVERITY_ICON_CLASSES = {
  INFO: 'bg-sky-50 ring-sky-200',
  SUCCESS: 'bg-emerald-50 ring-emerald-200',
  WARNING: 'bg-amber-50 ring-amber-200',
  ERROR: 'bg-red-50 ring-red-200',
}

/** @type {Record<NotificationSeverity, ToastTone>} */
export const TOAST_TONE_BY_SEVERITY = {
  INFO: 'info',
  SUCCESS: 'success',
  WARNING: 'warning',
  ERROR: 'error',
}

/** @type {Record<NotificationCategory, string>} */
export const CATEGORY_LABELS = {
  ORDER: 'Order',
  PAYMENT: 'Payment',
  STOCK: 'Stock',
  PRICING: 'Pricing',
  ACCOUNT: 'Account',
  APPROVAL: 'Approval',
  DELIVERY: 'Delivery',
  REVIEW: 'Review',
}

/**
 * @param {string} value
 * @returns {string}
 */
export function categoryLabel(value) {
  const category = categoryOf(value)
  return category ? CATEGORY_LABELS[category] : 'Update'
}

/**
 * Phase 8: the store's order steps are named more precisely than their "Order" category.
 * @type {Partial<Record<NotificationType, string>>}
 */
export const TYPE_LABELS = {
  NEW_ORDER: 'New order',
  ORDER_ACCEPTED: 'Order accepted',
  ORDER_READY: 'Ready for pickup',
  ORDER_AUTO_REJECTED: 'Auto-rejected order',
  // Phase 9
  DELIVERY_OFFER: 'Delivery offer',
  PICKUP_READY: 'Pickup ready',
  DELIVERY_REASSIGNED: 'Delivery reassigned',
  DELIVERY_CANCELLED: 'Delivery cancelled',
  PARTNER_ASSIGNED: 'Delivery partner',
  OUT_FOR_DELIVERY: 'On the way',
  ORDER_DELIVERED: 'Delivered',
  DELIVERY_FAILED: 'Delivery failed',
  DELIVERY_FAILED_ADMIN: 'Delivery failed',
  NO_DELIVERY_PARTNER: 'No delivery partner',
  // Phase 10
  STUCK_ORDER: 'Stuck order',
  // Phase 11: actions an assistant proposed.
  AI_APPROVAL_REQUESTED: 'Approval needed',
  AI_APPROVAL_DECIDED: 'Not approved',
  AI_ACTION_COMPLETED: 'Assistant action done',
  AI_ACTION_FAILED: 'Assistant action failed',
  // Phase 12: reviews and returns.
  NEW_REVIEW: 'New review',
  REVIEW_FLAGGED: 'Review to moderate',
  REVIEW_NOT_PUBLISHED: 'Review not published',
  REVIEW_REPLY: 'Store replied',
  RETURN_REQUESTED: 'Return requested',
  RETURN_ESCALATED: 'Return escalated',
  RETURN_APPROVED: 'Return approved',
  RETURN_REJECTED: 'Return rejected',
}

/**
 * The small label under a notification: its type's name when it has one, else its category.
 * @param {{ type: string, category: string }} notification
 * @returns {string}
 */
export function notificationLabel(notification) {
  return TYPE_LABELS[notification.type] ?? categoryLabel(notification.category)
}

/**
 * Accessible label of the connection-status dot.
 * @param {RealtimeStatus} status
 * @param {boolean} replaced
 * @returns {string}
 */
export function connectionLabel(status, replaced) {
  if (replaced) return 'Live updates are open in another tab'
  switch (status) {
    case 'live':
      return 'Live updates on'
    case 'connecting':
      return 'Connecting to live updates'
    case 'reconnecting':
      return 'Reconnecting to live updates'
    case 'offline':
      return 'Live updates offline'
  }
}

/** @type {Record<RealtimeStatus, string>} */
export const CONNECTION_DOT_CLASSES = {
  live: 'bg-emerald-500',
  connecting: 'bg-amber-400 animate-pulse',
  reconnecting: 'bg-amber-400 animate-pulse',
  offline: 'bg-slate-400',
}
