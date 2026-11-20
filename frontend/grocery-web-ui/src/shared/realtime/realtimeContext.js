import { createContext } from 'react'

/** @typedef {import('./messages').NotificationView} NotificationView */
/** @typedef {import('./messages').RealtimeStatus} RealtimeStatus */

/**
 * Called for every `notification` event of the stream, after it reached the cache. `isNew` is false for a
 * notification the cached inbox already held (a replay after reconnecting).
 * @typedef {(notification: NotificationView, meta: { isNew: boolean }) => void} NotificationListener
 */

/**
 * @typedef {object} RealtimeContextValue
 * @property {RealtimeStatus} status Connection state of the live-updates stream; `offline` while signed out.
 * @property {boolean} replaced Another tab took over the live connection (`session` REPLACED); this tab is not receiving updates.
 * @property {() => void} takeOver "Use this tab": reconnects this tab after REPLACED (the oldest other stream is closed by the server).
 * @property {() => () => void} suppressToasts Suppresses notification toasts while the caller is mounted (the inbox page shows them already). Returns the release function — call it from an effect and return it as the cleanup.
 * @property {(listener: NotificationListener) => () => void} subscribeNotifications Phase 8: listens to live notifications (the store's new-order sound alert). Returns the unsubscribe function — call it from an effect and return it as the cleanup.
 */

const noop = () => undefined

/**
 * Outside the provider (or signed out) nothing is live.
 * @type {RealtimeContextValue}
 */
export const OFFLINE_REALTIME = {
  status: 'offline',
  replaced: false,
  takeOver: noop,
  suppressToasts: () => noop,
  subscribeNotifications: () => noop,
}

export const RealtimeContext = createContext(OFFLINE_REALTIME)
