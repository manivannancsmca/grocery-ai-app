import type { QueryClient, QueryKey } from '@tanstack/react-query'
import type { PageResponse } from '../../shared/api/types'
import { type NotificationListParams, notificationKeys, type NotificationView } from './api'

/** Direct cache updates, so the bell and the inbox react at once; a refetch follows and has the last word. */

type NotificationPage = PageResponse<NotificationView>

function paramsOf(queryKey: QueryKey): NotificationListParams {
  const params = queryKey[2]
  return typeof params === 'object' && params !== null ? (params as NotificationListParams) : {}
}

function cachedLists(queryClient: QueryClient) {
  return queryClient.getQueryCache().findAll({ queryKey: notificationKeys.lists() })
}

function updateLists(queryClient: QueryClient, update: (entry: NotificationView) => NotificationView): void {
  queryClient.setQueriesData<NotificationPage>({ queryKey: notificationKeys.lists() }, (page) =>
    page ? { ...page, content: page.content.map(update) } : page,
  )
}

/**
 * A live notification goes on top of every cached first page it belongs to, and an unread one bumps the
 * unread count. Returns false when the notification was already cached (a replay after reconnecting).
 */
export function addNotificationToCache(queryClient: QueryClient, notification: NotificationView): boolean {
  const lists = cachedLists(queryClient)
  const known = lists.some((query) => (query.state.data as NotificationPage | undefined)?.content.some((entry) => entry.id === notification.id))
  if (known) return false

  for (const query of lists) {
    const params = paramsOf(query.queryKey)
    if ((params.page ?? 0) !== 0 || (params.unread && notification.read)) continue
    queryClient.setQueryData<NotificationPage>(query.queryKey, (page) => {
      if (!page) return page
      const size = Math.max(params.size ?? page.size, 1)
      const totalElements = page.totalElements + 1
      return {
        ...page,
        content: [notification, ...page.content].slice(0, size),
        totalElements,
        totalPages: Math.ceil(totalElements / size),
      }
    })
  }
  if (!notification.read) {
    queryClient.setQueryData<number>(notificationKeys.unreadCount(), (count) => (count === undefined ? count : count + 1))
  }
  return true
}

/** Marks one notification read in every cached list (it stays in place until the refetch). */
export function markReadInCache(queryClient: QueryClient, notification: NotificationView): void {
  updateLists(queryClient, (entry) => (entry.id === notification.id ? { ...entry, read: true } : entry))
  if (!notification.read) {
    queryClient.setQueryData<number>(notificationKeys.unreadCount(), (count) => (count === undefined ? count : Math.max(0, count - 1)))
  }
}

export function markAllReadInCache(queryClient: QueryClient): void {
  updateLists(queryClient, (entry) => (entry.read ? entry : { ...entry, read: true }))
  queryClient.setQueryData<number>(notificationKeys.unreadCount(), (count) => (count === undefined ? count : 0))
}
