import { useContext, useEffect, useEffectEvent } from 'react'
import { RealtimeContext } from './realtimeContext'

/** @typedef {import('./messages').RealtimeStatus} RealtimeStatus */
/** @typedef {import('./realtimeContext').NotificationListener} NotificationListener */
/** @typedef {import('./realtimeContext').RealtimeContextValue} RealtimeContextValue */

/** @returns {RealtimeContextValue} */
export function useRealtime() {
  return useContext(RealtimeContext)
}

/** @returns {RealtimeStatus} */
export function useRealtimeStatus() {
  return useContext(RealtimeContext).status
}

/**
 * True while the live-updates stream is open. Pages that used to poll keep only a slow fallback poll while
 * this is false (connecting, reconnecting, offline, another tab holds the stream, signed out).
 * @returns {boolean}
 */
export function useRealtimeLive() {
  return useContext(RealtimeContext).status === 'live'
}

/**
 * No notification toasts while the calling component is mounted (the notifications page).
 * @returns {void}
 */
export function useSuppressNotificationToasts() {
  const { suppressToasts } = useContext(RealtimeContext)
  useEffect(() => suppressToasts(), [suppressToasts])
}

/**
 * Calls `listener` (always its latest version) for every live notification while the caller is mounted.
 * @param {NotificationListener} listener
 * @returns {void}
 */
export function useLiveNotifications(listener) {
  const { subscribeNotifications } = useContext(RealtimeContext)
  const onNotification = useEffectEvent(listener)
  useEffect(
    () => subscribeNotifications((notification, meta) => onNotification(notification, meta)),
    [subscribeNotifications],
  )
}
