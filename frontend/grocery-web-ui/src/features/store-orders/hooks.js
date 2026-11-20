import { useMutation, useMutationState, useQuery, useQueryClient } from '@tanstack/react-query'
import { ApiError } from '../../shared/api/problemDetail'
import { useRealtimeLive } from '../../shared/realtime/useRealtime'
import { indiaDay } from '../../shared/utils/indiaDate'
import { restaurantApi, restaurantKeys } from '../restaurant/api'
import { storeReportKeys } from '../store-reports/api'
import { vendorApi, vendorKeys } from '../vendor/api'
import {
  acceptStoreOrder,
  fetchStoreDeliveries,
  listStoreOrders,
  markStoreOrderPreparing,
  markStoreOrderReady,
  rejectStoreOrder,
  STORE_ORDER_LIMITS,
  storeOrderKeys,
} from './api'
import { optimisticOrder } from './optimistic'

/** @typedef {import('../../shared/api/types').PageResponse} PageResponse */
/** @typedef {import('./api').RejectStoreOrderRequest} RejectStoreOrderRequest */
/** @typedef {import('./api').StoreKind} StoreKind */
/** @typedef {import('./api').StoreOrderListParams} StoreOrderListParams */
/** @typedef {import('./api').StoreOrderView} StoreOrderView */

/**
 * The board follows live `STORE_ORDERS_CHANGED` signals and `NEW_ORDER` notifications (the realtime layer
 * invalidates it). While the live stream is not open, a 30 s poll is the fallback.
 */
export const STORE_BOARD_FALLBACK_POLL_MS = 30_000

/**
 * @param {boolean} realtimeLive
 * @returns {number | false}
 */
export function storeBoardPollInterval(realtimeLive) {
  return realtimeLive ? false : STORE_BOARD_FALLBACK_POLL_MS
}

/** @type {StoreOrderListParams} */
export const ACTIVE_BOARD_PARAMS = { status: 'ACTIVE', page: 0, size: STORE_ORDER_LIMITS.pageSize }

/** How many closed orders of each kind are read for the "Closed today" list (newest first). */
const CLOSED_PAGE_SIZE = 50

/**
 * When a PICKED_UP order left the store: `pickedUpAt` if sent, else its PICKED_UP timeline entry.
 * @param {StoreOrderView} order
 * @returns {string | null}
 */
export function pickedUpAt(order) {
  return order.pickedUpAt ?? order.timeline.findLast((entry) => entry.status === 'PICKED_UP')?.at ?? null
}

/**
 * When an order left the board: picked up, rejected or cancelled at, else ready / received at.
 * @param {StoreOrderView} order
 * @returns {string}
 */
export function closedAt(order) {
  if (order.status === 'PICKED_UP') return pickedUpAt(order) ?? order.readyAt ?? order.receivedAt
  return order.rejection?.at ?? order.cancellation?.at ?? order.receivedAt
}

/** @type {PageResponse<StoreOrderView>} */
const EMPTY_PAGE = { content: [], page: 0, size: 0, totalElements: 0, totalPages: 0 }

/**
 * Picked-up orders; a store service from before Phase 9 rejects the new status (400) and counts as none.
 * @param {StoreKind} kind
 * @returns {Promise<PageResponse<StoreOrderView>>}
 */
async function listPickedUp(kind) {
  try {
    return await listStoreOrders(kind, { status: 'PICKED_UP', page: 0, size: CLOSED_PAGE_SIZE })
  } catch (error) {
    if (error instanceof ApiError && error.status === 400) return EMPTY_PAGE
    throw error
  }
}

/**
 * NEW, ACCEPTED, PREPARING and READY_FOR_PICKUP orders: NEW by `acceptBy` first, then the rest newest first.
 * @param {StoreKind} kind
 */
export function useActiveStoreOrders(kind) {
  const live = useRealtimeLive()
  return useQuery({
    queryKey: storeOrderKeys.list(kind, ACTIVE_BOARD_PARAMS),
    queryFn: () => listStoreOrders(kind, ACTIVE_BOARD_PARAMS),
    refetchInterval: storeBoardPollInterval(live),
  })
}

/**
 * Orders picked up, rejected or cancelled on `today` (India time, `YYYY-MM-DD`), newest first. The list
 * endpoint has no date filter, so the latest PICKED_UP, REJECTED and CANCELLED pages are read and narrowed to
 * that day here.
 * @param {StoreKind} kind
 * @param {string} today
 */
export function useClosedTodayStoreOrders(kind, today) {
  const live = useRealtimeLive()
  return useQuery({
    queryKey: storeOrderKeys.closedToday(kind, today),
    queryFn: async () => {
      const [pickedUp, rejected, cancelled] = await Promise.all([
        listPickedUp(kind),
        listStoreOrders(kind, { status: 'REJECTED', page: 0, size: CLOSED_PAGE_SIZE }),
        listStoreOrders(kind, { status: 'CANCELLED', page: 0, size: CLOSED_PAGE_SIZE }),
      ])
      return [...pickedUp.content, ...rejected.content, ...cancelled.content]
        .filter((order) => indiaDay(closedAt(order)) === today)
        .sort((a, b) => Date.parse(closedAt(b)) - Date.parse(closedAt(a)))
    },
    refetchInterval: storeBoardPollInterval(live),
  })
}

/** The partner's position is not pushed to stores: while orders wait for pickup, the board reads it this often. */
export const STORE_DELIVERIES_POLL_MS = 15_000

/**
 * Who collects each order still at the store (delivery-service), polled while `enabled` (orders wait for pickup).
 * `null` when the running delivery-service is older than this view (404): the board then just says "waiting for a
 * delivery partner", as before, and stops asking.
 * @param {StoreKind} kind
 * @param {boolean} enabled
 */
export function useStoreDeliveries(kind, enabled) {
  return useQuery({
    queryKey: storeOrderKeys.deliveries(kind),
    queryFn: async () => {
      try {
        return await fetchStoreDeliveries()
      } catch (error) {
        if (error instanceof ApiError && error.status === 404) return null
        throw error
      }
    },
    enabled,
    refetchInterval: (query) => (query.state.data === null ? false : STORE_DELIVERIES_POLL_MS),
  })
}

/**
 * @typedef {(
 *   | { type: 'accept', readyInMinutes: number }
 *   | { type: 'reject', body: RejectStoreOrderRequest }
 *   | { type: 'preparing' }
 *   | { type: 'ready' }
 * )} StoreOrderAction
 */

/**
 * @param {StoreKind} kind
 * @param {string} orderId
 * @param {StoreOrderAction} action
 * @returns {Promise<StoreOrderView>}
 */
function runAction(kind, orderId, action) {
  switch (action.type) {
    case 'accept':
      return acceptStoreOrder(kind, orderId, action.readyInMinutes)
    case 'reject':
      return rejectStoreOrder(kind, orderId, action.body)
    case 'preparing':
      return markStoreOrderPreparing(kind, orderId)
    case 'ready':
      return markStoreOrderReady(kind, orderId)
  }
}

const ACTIVE_STATUSES = new Set(['NEW', 'ACCEPTED', 'PREPARING', 'READY_FOR_PICKUP'])

/** @param {StoreKind} kind */
const storeOrderActionKey = (kind) => ['store-order-action', kind]

/**
 * Accept / reject / preparing / ready of one board card. Accept, preparing and ready move the card to its next
 * column on the click (optimistic); the server's answer then replaces it, and a failure puts the board back. The
 * board and the reports are re-read afterwards — also after a 409, so the board shows what really happened.
 * @param {StoreKind} kind
 */
export function useStoreOrderAction(kind) {
  const queryClient = useQueryClient()
  const boardKey = storeOrderKeys.list(kind, ACTIVE_BOARD_PARAMS)
  return useMutation({
    mutationKey: storeOrderActionKey(kind),
    mutationFn: ({ orderId, action }) => runAction(kind, orderId, action),
    onMutate: async ({ orderId, action }) => {
      // A board read finishing now would put the card back where it was.
      await queryClient.cancelQueries({ queryKey: boardKey })
      const previous = queryClient.getQueryData(boardKey)
      const now = Date.now()
      queryClient.setQueryData(boardKey, (page) =>
        page
          ? {
              ...page,
              content: page.content.map((entry) =>
                entry.orderId === orderId ? (optimisticOrder(entry, action, now) ?? entry) : entry,
              ),
            }
          : page,
      )
      return { previous }
    },
    onError: (_error, _variables, context) => {
      if (context?.previous) queryClient.setQueryData(boardKey, context.previous)
    },
    onSuccess: (order) => {
      queryClient.setQueryData(boardKey, (page) =>
        page
          ? {
              ...page,
              content: ACTIVE_STATUSES.has(order.status)
                ? page.content.map((entry) => (entry.orderId === order.orderId ? order : entry))
                : page.content.filter((entry) => entry.orderId !== order.orderId),
            }
          : page,
      )
    },
    onSettled: () =>
      Promise.all([
        queryClient.invalidateQueries({ queryKey: storeOrderKeys.ofKind(kind) }),
        queryClient.invalidateQueries({ queryKey: storeReportKeys.ofKind(kind) }),
      ]),
  })
}

/**
 * The orders whose action is still on its way to the server: their cards show "Saving…" (they may have moved already).
 * @param {StoreKind} kind
 * @returns {ReadonlySet<string>}
 */
export function useSavingStoreOrderIds(kind) {
  const ids = useMutationState({
    filters: { mutationKey: storeOrderActionKey(kind), status: 'pending' },
    select: (mutation) => mutation.state.variables?.orderId,
  })
  return new Set(ids.filter((id) => Boolean(id)))
}

/**
 * The store the board belongs to, as its title shows it.
 * @typedef {object} StoreIdentity
 * @property {string | null} name Business name (vendor) or restaurant name; null while unknown.
 * @property {boolean | null} acceptingOrders A restaurant's "accepting orders" switch; null for a grocery store or while unknown.
 */

/**
 * The store's name for the board's title (owners with more than one account mixed them up) and, for a restaurant,
 * whether it takes orders now. Shares the cached profile of the dashboard and profile pages; while it loads or when it
 * cannot be read the board simply shows no name.
 * @param {StoreKind} kind
 * @returns {StoreIdentity}
 */
export function useStoreIdentity(kind) {
  const { data } = useQuery({
    queryKey: kind === 'VENDOR' ? vendorKeys.mine : restaurantKeys.mine,
    queryFn: kind === 'VENDOR' ? vendorApi.fetchMine : restaurantApi.fetchMine,
    staleTime: 60_000,
  })
  if (!data) return { name: null, acceptingOrders: null }
  if ('businessName' in data) return { name: data.businessName?.trim() || null, acceptingOrders: null }
  return { name: data.name?.trim() || null, acceptingOrders: data.acceptingOrders ?? null }
}
