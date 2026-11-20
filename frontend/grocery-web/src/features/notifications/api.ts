import { httpClient } from '../../shared/api/httpClient'
import { cleanParams, type PageResponse, segment } from '../../shared/api/types'
import type { NotificationView } from '../../shared/realtime/messages'

/** The caller's notification inbox (notification-service — Phase 7 contract §3). Any signed-in role. */

export type {
  NotificationCategory,
  NotificationSeverity,
  NotificationType,
  NotificationView,
} from '../../shared/realtime/messages'

export interface NotificationListParams {
  /** true → only unread (never sent as `false`). */
  unread?: boolean
  page?: number
  /** ≤ 50. */
  size?: number
}

/** The header bell shows the latest ten. */
export const BELL_NOTIFICATION_COUNT = 10
export const NOTIFICATION_PAGE_SIZE = 20

export const NOTIFICATION_ERRORS = { notFound: 'NOTIFICATION_NOT_FOUND' } as const

export const notificationKeys = {
  all: ['notifications'] as const,
  lists: () => [...notificationKeys.all, 'list'] as const,
  list: (params: NotificationListParams) => [...notificationKeys.lists(), params] as const,
  unreadCount: () => [...notificationKeys.all, 'unread-count'] as const,
}

/** Newest first. */
export async function listNotifications(params: NotificationListParams): Promise<PageResponse<NotificationView>> {
  const query = cleanParams({ unread: params.unread ? true : undefined, page: params.page, size: params.size })
  return (await httpClient.get<PageResponse<NotificationView>>('/notifications', { params: query })).data
}

export async function fetchUnreadCount(): Promise<number> {
  return (await httpClient.get<{ count: number }>('/notifications/unread-count')).data.count
}

/** 204, idempotent. Somebody else's or an unknown id → 404 NOTIFICATION_NOT_FOUND. */
export async function markNotificationRead(id: string): Promise<void> {
  await httpClient.post(`/notifications/${segment(id)}/read`)
}

/** `{ updated }` = how many were unread. */
export async function markAllNotificationsRead(): Promise<number> {
  return (await httpClient.post<{ updated: number }>('/notifications/read-all')).data.updated
}
