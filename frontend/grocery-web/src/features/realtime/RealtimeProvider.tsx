import { useQueryClient } from '@tanstack/react-query'
import { type ReactNode, useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { getAccessToken, markSuspended, refreshSession } from '../../shared/auth/session'
import { useAuth } from '../../shared/auth/useAuth'
import { useToast } from '../../shared/components/toast/useToast'
import { Button } from '../../shared/components/ui'
import { createLastEventIdStore } from '../../shared/realtime/lastEventIdStore'
import type { RealtimeStatus } from '../../shared/realtime/messages'
import { type NotificationListener, RealtimeContext, type RealtimeContextValue } from '../../shared/realtime/realtimeContext'
import { createSseClient, type SseClient } from '../../shared/realtime/sseClient'
import { createRealtimeDispatcher } from './dispatcher'

interface ConnectionState {
  /** Whose stream the state describes (a later session starts from scratch). */
  userId: string | null
  status: RealtimeStatus
  replaced: boolean
}

function ReplacedBanner({ onTakeOver }: { onTakeOver: () => void }) {
  return (
    <div className="pointer-events-none fixed inset-x-0 top-20 z-40 flex justify-center px-4">
      <div
        role="status"
        className="pointer-events-auto flex flex-wrap items-center gap-3 rounded-xl border border-amber-200 bg-amber-50 px-4 py-2.5 text-sm text-amber-900 shadow-lg"
      >
        <span>Live updates are open in another tab.</span>
        <Button variant="secondary" size="sm" onClick={onTakeOver}>
          Use this tab
        </Button>
      </div>
    </div>
  )
}

/**
 * The live-updates connection of a signed-in user (Phase 7). Opens the SSE stream while the session is
 * authenticated, closes it on sign-out, and turns its events into cache updates, invalidations and toasts.
 * Exposes the connection status through `RealtimeContext` (`useRealtime*` hooks).
 *
 * - `session` REVOKED (account suspended) → the existing suspended flow (`/account-suspended`).
 * - `session` REPLACED (a 6th stream elsewhere) → banner "Live updates are open in another tab" + "Use this tab".
 */
export function RealtimeProvider({ children }: { children: ReactNode }) {
  const { status: sessionStatus, user } = useAuth()
  const queryClient = useQueryClient()
  const toast = useToast()
  const userId = sessionStatus === 'authenticated' ? (user?.id ?? null) : null

  const [connection, setConnection] = useState<ConnectionState>({ userId: null, status: 'offline', replaced: false })
  const clientRef = useRef<SseClient | null>(null)
  const toastSuppressors = useRef(0)
  const notificationListeners = useRef(new Set<NotificationListener>())

  useEffect(() => {
    if (userId === null) return
    const listeners = notificationListeners.current
    const dispatcher = createRealtimeDispatcher({
      queryClient,
      toast,
      toastsSuppressed: () => toastSuppressors.current > 0,
      refreshToken: refreshSession,
      onNotification: (notification, meta) => {
        for (const listener of [...listeners]) listener(notification, meta)
      },
    })
    const client = createSseClient({
      getAccessToken,
      refreshAccessToken: refreshSession,
      lastEventId: createLastEventIdStore(userId),
      onEvent: dispatcher.handle,
      onStatusChange: (status) =>
        setConnection((current) => ({
          userId,
          status,
          // A fresh start (or an open stream) ends the "open in another tab" state.
          replaced: status === 'connecting' || status === 'live' ? false : current.userId === userId && current.replaced,
        })),
      onRevoked: (message) => markSuspended(message),
      onReplaced: () => setConnection({ userId, status: 'offline', replaced: true }),
    })
    clientRef.current = client
    client.start()
    return () => {
      clientRef.current = null
      client.stop()
      dispatcher.dispose()
    }
  }, [userId, queryClient, toast])

  const takeOver = useCallback(() => clientRef.current?.start(), [])

  const suppressToasts = useCallback(() => {
    toastSuppressors.current++
    let released = false
    return () => {
      if (released) return
      released = true
      toastSuppressors.current--
    }
  }, [])

  const subscribeNotifications = useCallback((listener: NotificationListener) => {
    const listeners = notificationListeners.current
    listeners.add(listener)
    return () => {
      listeners.delete(listener)
    }
  }, [])

  const current = userId !== null && connection.userId === userId
  const status: RealtimeStatus = current ? connection.status : 'offline'
  const replaced = current && connection.replaced

  const value = useMemo<RealtimeContextValue>(
    () => ({ status, replaced, takeOver, suppressToasts, subscribeNotifications }),
    [status, replaced, takeOver, suppressToasts, subscribeNotifications],
  )

  return (
    <RealtimeContext value={value}>
      {children}
      {replaced && <ReplacedBanner onTakeOver={takeOver} />}
    </RealtimeContext>
  )
}
