import { createContext } from 'react'
import type { NotificationView, RealtimeStatus } from './messages'

/**
 * Called for every `notification` event of the stream, after it reached the cache. `isNew` is false for a
 * notification the cached inbox already held (a replay after reconnecting).
 */
export type NotificationListener = (notification: NotificationView, meta: { isNew: boolean }) => void

export interface RealtimeContextValue {
  /** Connection state of the live-updates stream; `offline` while signed out. */
  status: RealtimeStatus
  /** Another tab took over the live connection (`session` REPLACED); this tab is not receiving updates. */
  replaced: boolean
  /** "Use this tab": reconnects this tab after REPLACED (the oldest other stream is closed by the server). */
  takeOver: () => void
  /**
   * Suppresses notification toasts while the caller is mounted (the inbox page shows them already).
   * Returns the release function — call it from an effect and return it as the cleanup.
   */
  suppressToasts: () => () => void
  /**
   * Phase 8: listens to live notifications (the store's new-order sound alert). Returns the unsubscribe
   * function — call it from an effect and return it as the cleanup.
   */
  subscribeNotifications: (listener: NotificationListener) => () => void
}

const noop = () => undefined

/** Outside the provider (or signed out) nothing is live. */
export const OFFLINE_REALTIME: RealtimeContextValue = {
  status: 'offline',
  replaced: false,
  takeOver: noop,
  suppressToasts: () => noop,
  subscribeNotifications: () => noop,
}

export const RealtimeContext = createContext<RealtimeContextValue>(OFFLINE_REALTIME)
