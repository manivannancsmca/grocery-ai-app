import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useAuth } from '../../shared/auth/useAuth'
import { useRealtimeLive } from '../../shared/realtime/useRealtime'
import {
  fetchUnreadCount,
  listNotifications,
  markAllNotificationsRead,
  markNotificationRead,
  notificationKeys,
} from './api'
import { markAllReadInCache, markReadInCache } from './cache'

/** @typedef {import('./api').NotificationListParams} NotificationListParams */

/** While the live stream is down the unread badge is re-read this often (live: on every notification instead). */
export const UNREAD_COUNT_FALLBACK_POLL_MS = 60_000

export function useUnreadNotificationCount() {
  const { isAuthenticated } = useAuth()
  const live = useRealtimeLive()
  return useQuery({
    queryKey: notificationKeys.unreadCount(),
    queryFn: fetchUnreadCount,
    enabled: isAuthenticated,
    refetchInterval: live ? false : UNREAD_COUNT_FALLBACK_POLL_MS,
  })
}

/**
 * One page of the inbox (newest first); the previous page stays on screen while the next one loads.
 * @param {NotificationListParams} params
 * @param {{ enabled?: boolean }} [options={}]
 */
export function useNotifications(params, options = {}) {
  const { isAuthenticated } = useAuth()
  return useQuery({
    queryKey: notificationKeys.list(params),
    queryFn: () => listNotifications(params),
    enabled: isAuthenticated && (options.enabled ?? true),
    placeholderData: keepPreviousData,
  })
}

/** Marks one notification read at once in the cache; the inbox and the badge are re-read afterwards. */
export function useMarkNotificationRead() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (notification) => markNotificationRead(notification.id),
    onMutate: async (notification) => {
      await queryClient.cancelQueries({ queryKey: notificationKeys.all })
      markReadInCache(queryClient, notification)
    },
    onSettled: () => queryClient.invalidateQueries({ queryKey: notificationKeys.all }),
  })
}

export function useMarkAllNotificationsRead() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: markAllNotificationsRead,
    onMutate: async () => {
      await queryClient.cancelQueries({ queryKey: notificationKeys.all })
      markAllReadInCache(queryClient)
    },
    onSettled: () => queryClient.invalidateQueries({ queryKey: notificationKeys.all }),
  })
}
