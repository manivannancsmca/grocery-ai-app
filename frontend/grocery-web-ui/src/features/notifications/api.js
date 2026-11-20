import { httpClient } from '../../shared/api/httpClient'
import { cleanParams, segment } from '../../shared/api/types'

/** @typedef {import('../../shared/api/types').PageResponse} PageResponse */
/** @typedef {import('../../shared/realtime/messages').NotificationView} NotificationView */

/** The caller's notification inbox (notification-service — Phase 7 contract §3). Any signed-in role. */

/** @typedef {import('../../shared/realtime/messages').NotificationCategory} NotificationCategory */
/** @typedef {import('../../shared/realtime/messages').NotificationSeverity} NotificationSeverity */
/** @typedef {import('../../shared/realtime/messages').NotificationType} NotificationType */
/** @typedef {import('../../shared/realtime/messages').NotificationView} NotificationView */

/**
 * @typedef {object} NotificationListParams
 * @property {boolean} [unread] true → only unread (never sent as `false`).
 * @property {number} [page]
 * @property {number} [size] ≤ 50.
 */

/** The header bell shows the latest ten. */
export const BELL_NOTIFICATION_COUNT = 10
export const NOTIFICATION_PAGE_SIZE = 20

export const NOTIFICATION_ERRORS = { notFound: 'NOTIFICATION_NOT_FOUND' }

export const notificationKeys = {
  all: ['notifications'],
  lists: () => [...notificationKeys.all, 'list'],
  list: (params) => [...notificationKeys.lists(), params],
  unreadCount: () => [...notificationKeys.all, 'unread-count'],
}

/**
 * Newest first.
 * @param {NotificationListParams} params
 * @returns {Promise<PageResponse<NotificationView>>}
 */
export async function listNotifications(params) {
  const query = cleanParams({ unread: params.unread ? true : undefined, page: params.page, size: params.size })
  return (await httpClient.get('/notifications', { params: query })).data
}

/** @returns {Promise<number>} */
export async function fetchUnreadCount() {
  return (await httpClient.get('/notifications/unread-count')).data.count
}

/**
 * 204, idempotent. Somebody else's or an unknown id → 404 NOTIFICATION_NOT_FOUND.
 * @param {string} id
 * @returns {Promise<void>}
 */
export async function markNotificationRead(id) {
  await httpClient.post(`/notifications/${segment(id)}/read`)
}

/**
 * `{ updated }` = how many were unread.
 * @returns {Promise<number>}
 */
export async function markAllNotificationsRead() {
  return (await httpClient.post('/notifications/read-all')).data.updated
}
