import { useContext, useEffect, useEffectEvent } from 'react'
import type { RealtimeStatus } from './messages'
import { type NotificationListener, RealtimeContext, type RealtimeContextValue } from './realtimeContext'

export function useRealtime(): RealtimeContextValue {
  return useContext(RealtimeContext)
}

export function useRealtimeStatus(): RealtimeStatus {
  return useContext(RealtimeContext).status
}

/**
 * True while the live-updates stream is open. Pages that used to poll keep only a slow fallback poll while
 * this is false (connecting, reconnecting, offline, another tab holds the stream, signed out).
 */
export function useRealtimeLive(): boolean {
  return useContext(RealtimeContext).status === 'live'
}

/** No notification toasts while the calling component is mounted (the notifications page). */
export function useSuppressNotificationToasts(): void {
  const { suppressToasts } = useContext(RealtimeContext)
  useEffect(() => suppressToasts(), [suppressToasts])
}

/** Calls `listener` (always its latest version) for every live notification while the caller is mounted. */
export function useLiveNotifications(listener: NotificationListener): void {
  const { subscribeNotifications } = useContext(RealtimeContext)
  const onNotification = useEffectEvent(listener)
  useEffect(() => subscribeNotifications((notification, meta) => onNotification(notification, meta)), [subscribeNotifications])
}
