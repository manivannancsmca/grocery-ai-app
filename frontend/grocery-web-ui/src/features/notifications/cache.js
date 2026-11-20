import { notificationKeys } from './api'

/** @typedef {import('@tanstack/react-query').QueryClient} QueryClient */
/** @typedef {import('@tanstack/react-query').QueryKey} QueryKey */
/** @typedef {import('../../shared/api/types').PageResponse} PageResponse */
/** @typedef {import('./api').NotificationListParams} NotificationListParams */
/** @typedef {import('./api').NotificationView} NotificationView */

/** Direct cache updates, so the bell and the inbox react at once; a refetch follows and has the last word. */

/** @typedef {PageResponse<NotificationView>} NotificationPage */

/**
 * @param {QueryKey} queryKey
 * @returns {NotificationListParams}
 */
function paramsOf(queryKey) {
  const params = queryKey[2]
  return typeof params === 'object' && params !== null ? params : {}
}

/** @param {QueryClient} queryClient */
function cachedLists(queryClient) {
  return queryClient.getQueryCache().findAll({ queryKey: notificationKeys.lists() })
}

/**
 * @param {QueryClient} queryClient
 * @param {(entry: NotificationView) => NotificationView} update
 * @returns {void}
 */
function updateLists(queryClient, update) {
  queryClient.setQueriesData({ queryKey: notificationKeys.lists() }, (page) =>
    page ? { ...page, content: page.content.map(update) } : page,
  )
}

/**
 * A live notification goes on top of every cached first page it belongs to, and an unread one bumps the
 * unread count. Returns false when the notification was already cached (a replay after reconnecting).
 * @param {QueryClient} queryClient
 * @param {NotificationView} notification
 * @returns {boolean}
 */
export function addNotificationToCache(queryClient, notification) {
  const lists = cachedLists(queryClient)
  const known = lists.some((query) => query.state.data?.content.some((entry) => entry.id === notification.id))
  if (known) return false

  for (const query of lists) {
    const params = paramsOf(query.queryKey)
    if ((params.page ?? 0) !== 0 || (params.unread && notification.read)) continue
    queryClient.setQueryData(query.queryKey, (page) => {
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
    queryClient.setQueryData(notificationKeys.unreadCount(), (count) => (count === undefined ? count : count + 1))
  }
  return true
}

/**
 * Marks one notification read in every cached list (it stays in place until the refetch).
 * @param {QueryClient} queryClient
 * @param {NotificationView} notification
 * @returns {void}
 */
export function markReadInCache(queryClient, notification) {
  updateLists(queryClient, (entry) => (entry.id === notification.id ? { ...entry, read: true } : entry))
  if (!notification.read) {
    queryClient.setQueryData(notificationKeys.unreadCount(), (count) =>
      count === undefined ? count : Math.max(0, count - 1),
    )
  }
}

/**
 * @param {QueryClient} queryClient
 * @returns {void}
 */
export function markAllReadInCache(queryClient) {
  updateLists(queryClient, (entry) => (entry.read ? entry : { ...entry, read: true }))
  queryClient.setQueryData(notificationKeys.unreadCount(), (count) => (count === undefined ? count : 0))
}
