import type { ToastTone } from '../../shared/components/toast/toastContext'
import {
  NOTIFICATION_CATEGORIES,
  NOTIFICATION_SEVERITIES,
  type NotificationCategory,
  type NotificationSeverity,
  type NotificationType,
  type RealtimeStatus,
} from '../../shared/realtime/messages'

export function severityOf(value: string): NotificationSeverity {
  return (NOTIFICATION_SEVERITIES as readonly string[]).includes(value) ? (value as NotificationSeverity) : 'INFO'
}

export function categoryOf(value: string): NotificationCategory | null {
  return (NOTIFICATION_CATEGORIES as readonly string[]).includes(value) ? (value as NotificationCategory) : null
}

/** Icon circle tints per severity (the app's Badge / Alert palette); the icon inside keeps its own colours. */
export const SEVERITY_ICON_CLASSES: Record<NotificationSeverity, string> = {
  INFO: 'bg-sky-50 ring-sky-200',
  SUCCESS: 'bg-emerald-50 ring-emerald-200',
  WARNING: 'bg-amber-50 ring-amber-200',
  ERROR: 'bg-red-50 ring-red-200',
}

export const TOAST_TONE_BY_SEVERITY: Record<NotificationSeverity, ToastTone> = {
  INFO: 'info',
  SUCCESS: 'success',
  WARNING: 'warning',
  ERROR: 'error',
}

export const CATEGORY_LABELS: Record<NotificationCategory, string> = {
  ORDER: 'Order',
  PAYMENT: 'Payment',
  STOCK: 'Stock',
  PRICING: 'Pricing',
  ACCOUNT: 'Account',
  APPROVAL: 'Approval',
  DELIVERY: 'Delivery',
  REVIEW: 'Review',
}

export function categoryLabel(value: string): string {
  const category = categoryOf(value)
  return category ? CATEGORY_LABELS[category] : 'Update'
}

/** Phase 8: the store's order steps are named more precisely than their "Order" category. */
export const TYPE_LABELS: Partial<Record<NotificationType, string>> = {
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

/** The small label under a notification: its type's name when it has one, else its category. */
export function notificationLabel(notification: { type: string; category: string }): string {
  return TYPE_LABELS[notification.type as NotificationType] ?? categoryLabel(notification.category)
}

/** Accessible label of the connection-status dot. */
export function connectionLabel(status: RealtimeStatus, replaced: boolean): string {
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

export const CONNECTION_DOT_CLASSES: Record<RealtimeStatus, string> = {
  live: 'bg-emerald-500',
  connecting: 'bg-amber-400 animate-pulse',
  reconnecting: 'bg-amber-400 animate-pulse',
  offline: 'bg-slate-400',
}
