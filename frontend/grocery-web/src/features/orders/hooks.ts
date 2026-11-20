import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { ApiError, hasErrorCode } from '../../shared/api/problemDetail'
import { useRealtimeLive } from '../../shared/realtime/useRealtime'
import { cartKeys, type CartView, emptyCart } from '../cart/api'
import { cancelOrder, fetchOrder, listMyOrders, type MyOrderListParams, ORDER_ERRORS, orderKeys, type OrderView, placeOrder, type PlaceOrderRequest } from './api'
import { checkoutKeyFor, clearCheckoutKey, renewCheckoutKey } from './checkoutAttempt'
import { isTerminalStatus } from './labels'

/**
 * Phase 7: an order follows live `ORDER_UPDATED` signals (the realtime layer invalidates it). While the live
 * stream is not open (connecting, reconnecting, offline, another tab holds it), a slow poll is the fallback.
 */
export const ORDER_FALLBACK_POLL_MS = 30_000

/** Whether an order in this state can still change: everything but REJECTED and CANCELLED with nothing left to refund. */
export function orderStillMoving(order: OrderView): boolean {
  if (!isTerminalStatus(order.status)) return true
  // A paid order that was cancelled is refunded by payment-service; follow it until the refund lands.
  return order.payment.status === 'REFUND_PENDING' || (order.status === 'CANCELLED' && order.payment.status === 'PAID')
}

/** How often to re-read an order: never while live updates are on or once it can no longer change. */
export function orderPollInterval(order: OrderView | undefined, realtimeLive: boolean): number | false {
  if (!order || realtimeLive || !orderStillMoving(order)) return false
  return ORDER_FALLBACK_POLL_MS
}

/**
 * One order of the signed-in customer. Live `ORDER_UPDATED` signals refetch it; a slow poll covers the time
 * the stream is down. A failed refetch keeps the last answer on screen.
 */
export function useOrder(id: string) {
  const live = useRealtimeLive()
  return useQuery({
    queryKey: orderKeys.detail(id),
    queryFn: () => fetchOrder(id),
    enabled: id !== '',
    staleTime: 0,
    refetchInterval: (query) => orderPollInterval(query.state.data, live),
  })
}

/**
 * A page of the customer's orders, newest first. Live `ORDER_UPDATED` signals refetch it; with `followActive` (the
 * orders page) it also re-reads itself slowly while the stream is down and the page has an order still under way.
 */
export function useMyOrders(params: MyOrderListParams, { followActive = false }: { followActive?: boolean } = {}) {
  const live = useRealtimeLive()
  return useQuery({
    queryKey: orderKeys.list(params),
    queryFn: () => listMyOrders(params),
    placeholderData: keepPreviousData,
    refetchInterval: (query) =>
      followActive && !live && query.state.data?.content.some((order) => !isTerminalStatus(order.status)) ? ORDER_FALLBACK_POLL_MS : false,
  })
}

/** Errors after which nothing was stored on the server, so the next attempt must not reuse the key. */
function isDefinitiveRefusal(error: unknown): boolean {
  return error instanceof ApiError && error.kind === 'http' && error.status !== null && error.status < 500
}

export interface PlaceOrderVariables {
  body: PlaceOrderRequest
  /** `checkoutFingerprint(addressId, total)` of the previewed order. */
  fingerprint: string
}

/**
 * `POST /api/orders` with the attempt's `Idempotency-Key`. A network error or 5xx keeps the key, so the
 * retry cannot create a second order; `422 IDEMPOTENCY_KEY_REUSED` (the same key with another body, e.g. an
 * edited note) is retried once with a new key. Success and definitive refusals forget the key.
 */
export function usePlaceOrder() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: async ({ body, fingerprint }: PlaceOrderVariables) => {
      try {
        return await placeOrder(body, checkoutKeyFor(fingerprint))
      } catch (error) {
        if (hasErrorCode(error, ORDER_ERRORS.keyReused)) return placeOrder(body, renewCheckoutKey(fingerprint))
        throw error
      }
    },
    onSuccess: (order) => {
      clearCheckoutKey()
      queryClient.setQueryData(orderKeys.detail(order.id), order)
      void queryClient.invalidateQueries({ queryKey: orderKeys.lists() })
      // cart-service empties the cart from the OrderCreated event a moment later, so re-reading it now would
      // still show the ordered items. Show it empty; it is re-read the next time the cart is opened.
      const currency = queryClient.getQueryData<CartView>(cartKeys.cart())?.currency
      queryClient.setQueryData(cartKeys.cart(), emptyCart(currency))
      void queryClient.invalidateQueries({ queryKey: cartKeys.all, refetchType: 'none' })
    },
    onError: (error) => {
      if (isDefinitiveRefusal(error)) clearCheckoutKey()
      if (
        hasErrorCode(error, ORDER_ERRORS.checkoutBlocked, ORDER_ERRORS.totalChanged, ORDER_ERRORS.addressNotFound, ORDER_ERRORS.couponNotApplicable)
      ) {
        void queryClient.invalidateQueries({ queryKey: cartKeys.all })
      }
    },
  })
}

/** Customer cancel; the answer (or a 409) refreshes the order and the lists. */
export function useCancelOrder(id: string) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (reason: string | null) => cancelOrder(id, reason),
    onSuccess: (order) => queryClient.setQueryData(orderKeys.detail(id), order),
    onSettled: () => queryClient.invalidateQueries({ queryKey: orderKeys.all }),
  })
}
