import type { QueryClient, QueryKey } from '@tanstack/react-query'
import type { ToastApi } from '../../shared/components/toast/toastContext'
import { parseNotification, parseSignal, type NotificationView, type Signal, STREAM_EVENTS } from '../../shared/realtime/messages'
import type { SseEvent } from '../../shared/realtime/sseParser'
import { catalogKeys, type ProductDetail, type ProductSearchResponse } from '../catalog/api'
import { notificationKeys } from '../notifications/api'
import { addNotificationToCache } from '../notifications/cache'
import { applyDeliveryLocation } from '../order-tracking/cache'
import { severityOf, TOAST_TONE_BY_SEVERITY } from '../notifications/style'
import { invalidationForNotification, invalidationForSignal, type InvalidationPlan } from './invalidation'

/** At most this many notification toasts per window; one "more new notifications" toast covers the rest. */
export const NOTIFICATION_TOAST_LIMIT = { perWindow: 3, windowMs: 10_000 } as const

/** Invalidations are collected for this long, so a burst (a replay after reconnecting) refetches each query once. */
export const INVALIDATION_BATCH_MS = 150

export interface RealtimeDispatcherOptions {
  queryClient: QueryClient
  toast: ToastApi
  /** True while the inbox page is open: it shows new notifications itself. */
  toastsSuppressed: () => boolean
  /** The app's silent token refresh. */
  refreshToken: () => Promise<unknown>
  /** Every parsed notification, after the cache update (Phase 8: the store's new-order alert listens here). */
  onNotification?: (notification: NotificationView, meta: { isNew: boolean }) => void
  batchMs?: number
  now?: () => number
}

export interface RealtimeDispatcher {
  /** One `notification` or `signal` event from the stream (anything else is ignored). */
  handle: (event: SseEvent) => void
  /** Drops pending work (sign-out, unmount). */
  dispose: () => void
}

/**
 * Turns stream events into cache work:
 * - `notification` → top of the cached inbox lists + unread count, refetch of the inbox and the badge, the
 *   invalidation map for its type, and a toast (severity → tone; rate-limited; none while the inbox is open).
 * - `signal` → the invalidation map. STOCK_CHANGED also patches `inStock` into the cached product and search
 *   results at once, so "Out of stock" shows even before the refetch returns. DELIVERY_LOCATION (Phase 9)
 *   only patches the partner's position (and ETA) into the cached tracking view; nothing is refetched.
 */
export function createRealtimeDispatcher(options: RealtimeDispatcherOptions): RealtimeDispatcher {
  const { queryClient, toast } = options
  const batchMs = options.batchMs ?? INVALIDATION_BATCH_MS
  const now = options.now ?? Date.now

  let pending = new Map<string, QueryKey>()
  let everything = false
  let renewToken = false
  let timer: ReturnType<typeof setTimeout> | null = null
  let disposed = false

  let windowStart = Number.NEGATIVE_INFINITY
  let toastsInWindow = 0
  let summaryShown = false

  function flush(): void {
    timer = null
    const keys = [...pending.values()]
    const all = everything
    const renew = renewToken
    pending = new Map()
    everything = false
    renewToken = false
    // Profiles are re-read with the renewed token (and its new claims).
    const before: Promise<unknown> = renew ? options.refreshToken().catch(() => undefined) : Promise.resolve()
    void before
      .then(async () => {
        if (disposed) return
        if (all) await queryClient.invalidateQueries()
        else await Promise.all(keys.map((queryKey) => queryClient.invalidateQueries({ queryKey })))
      })
      .catch(() => undefined)
  }

  function schedule(plan: InvalidationPlan): void {
    if (disposed) return
    if (plan.everything) everything = true
    if (plan.refreshToken) renewToken = true
    for (const key of plan.keys) pending.set(JSON.stringify(key), key)
    if (timer === null && (everything || renewToken || pending.size > 0)) timer = setTimeout(flush, batchMs)
  }

  function showToast(notification: NotificationView): void {
    if (notification.read || options.toastsSuppressed()) return
    const time = now()
    if (time - windowStart >= NOTIFICATION_TOAST_LIMIT.windowMs) {
      windowStart = time
      toastsInWindow = 0
      summaryShown = false
    }
    if (toastsInWindow < NOTIFICATION_TOAST_LIMIT.perWindow) {
      toastsInWindow++
      toast.show(TOAST_TONE_BY_SEVERITY[severityOf(notification.severity)], notification.title, {
        description: notification.body || undefined,
      })
      return
    }
    if (!summaryShown) {
      summaryShown = true
      toast.info('More new notifications', { description: 'Open the notifications menu to see them all.' })
    }
  }

  function patchStock(signal: Extract<Signal, { type: 'STOCK_CHANGED' }>): void {
    const { productId, inStock } = signal.data
    if (!productId || inStock === undefined) return
    queryClient.setQueryData<ProductDetail>(catalogKeys.product(productId), (product) => (product ? { ...product, inStock } : product))
    queryClient.setQueriesData<ProductSearchResponse>({ queryKey: catalogKeys.searches() }, (page) =>
      page?.content.some((card) => card.id === productId)
        ? { ...page, content: page.content.map((card) => (card.id === productId ? { ...card, inStock } : card)) }
        : page,
    )
  }

  function handle(event: SseEvent): void {
    if (disposed) return
    if (event.type === STREAM_EVENTS.notification) {
      const notification = parseNotification(event.data)
      if (!notification) return
      const isNew = addNotificationToCache(queryClient, notification)
      schedule({ keys: [notificationKeys.all] })
      schedule(invalidationForNotification(notification))
      if (isNew) showToast(notification)
      try {
        options.onNotification?.(notification, { isNew })
      } catch {
        // A listener's failure must never break the stream.
      }
      return
    }
    if (event.type === STREAM_EVENTS.signal) {
      const signal = parseSignal(event.data)
      if (!signal) return
      if (signal.type === 'STOCK_CHANGED') patchStock(signal)
      // Phase 9: the partner's new position goes straight into the customer's tracking view (no refetch).
      if (signal.type === 'DELIVERY_LOCATION') applyDeliveryLocation(queryClient, signal.data)
      schedule(invalidationForSignal(signal))
    }
  }

  function dispose(): void {
    disposed = true
    if (timer !== null) clearTimeout(timer)
    timer = null
    pending = new Map()
  }

  return { handle, dispose }
}
