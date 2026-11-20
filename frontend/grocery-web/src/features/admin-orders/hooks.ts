import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useRealtimeLive } from '../../shared/realtime/useRealtime'
import { orderKeys } from '../orders/api'
import { paymentKeys } from '../payments/api'
import { adminPaymentKeys } from '../admin-payments/api'
import {
  adminCancelOrder,
  type AdminOrderListParams,
  adminOrderKeys,
  fetchAdminOrder,
  fetchOrderEvents,
  fetchOrderStats,
  fetchStuckOrders,
  listAdminOrders,
} from './api'

/**
 * Phase 7: the monitors and the dashboard stats refresh on live `ADMIN_ORDERS_CHANGED` signals. While the
 * live stream is down they fall back to polling: the stats every minute (as before), the monitors every 30 s.
 */
export const ORDER_STATS_REFRESH_MS = 60_000
export const ADMIN_MONITOR_FALLBACK_POLL_MS = 30_000

export function useAdminOrders(params: AdminOrderListParams) {
  const live = useRealtimeLive()
  return useQuery({
    queryKey: adminOrderKeys.list(params),
    queryFn: () => listAdminOrders(params),
    placeholderData: keepPreviousData,
    refetchInterval: live ? false : ADMIN_MONITOR_FALLBACK_POLL_MS,
  })
}

export function useAdminOrder(id: string) {
  return useQuery({ queryKey: adminOrderKeys.detail(id), queryFn: () => fetchAdminOrder(id) })
}

/**
 * Phase 10: the watchdog runs every minute. Detections and resolutions arrive as ADMIN_ORDERS_CHANGED (the key is
 * under `adminOrderKeys.all`); a slow poll keeps `minutesOverdue` current while live, a faster one while not.
 */
export const STUCK_ORDERS_LIVE_POLL_MS = 60_000

export function useStuckOrders() {
  const live = useRealtimeLive()
  return useQuery({
    queryKey: adminOrderKeys.stuck(),
    queryFn: fetchStuckOrders,
    refetchInterval: live ? STUCK_ORDERS_LIVE_POLL_MS : ADMIN_MONITOR_FALLBACK_POLL_MS,
  })
}

/** The order's event log (Events tab); refreshed with the order on ADMIN_ORDERS_CHANGED. */
export function useOrderEvents(id: string) {
  return useQuery({ queryKey: adminOrderKeys.events(id), queryFn: () => fetchOrderEvents(id) })
}

export function useOrderStats(date: string | null = null) {
  const live = useRealtimeLive()
  return useQuery({
    queryKey: adminOrderKeys.stats(date),
    queryFn: () => fetchOrderStats(date),
    refetchInterval: live ? false : ORDER_STATS_REFRESH_MS,
  })
}

/** Admin cancel; lists, the order, its payment and the stats are refreshed afterwards (also after a 409). */
export function useAdminCancelOrder() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: ({ id, reason }: { id: string; reason: string }) => adminCancelOrder(id, reason),
    onSuccess: (order) => queryClient.setQueryData(adminOrderKeys.detail(order.id), order),
    onSettled: (_data, _error, { id }) =>
      Promise.all([
        queryClient.invalidateQueries({ queryKey: adminOrderKeys.all }),
        queryClient.invalidateQueries({ queryKey: adminOrderKeys.allStats() }),
        queryClient.invalidateQueries({ queryKey: paymentKeys.forOrder(id) }),
        queryClient.invalidateQueries({ queryKey: adminPaymentKeys.all }),
        queryClient.invalidateQueries({ queryKey: orderKeys.detail(id) }),
      ]),
  })
}
