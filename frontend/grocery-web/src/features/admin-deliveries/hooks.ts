import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useRealtimeLive } from '../../shared/realtime/useRealtime'
import { ADMIN_MONITOR_FALLBACK_POLL_MS } from '../admin-orders/hooks'
import {
  type AdminDeliveryListParams,
  adminDeliveryKeys,
  fetchAdminDelivery,
  listAdminDeliveries,
  listOnlinePartners,
  reassignDelivery,
  type ReassignRequest,
} from './api'

/**
 * The monitor refreshes on live ADMIN_DELIVERIES_CHANGED signals and NO_DELIVERY_PARTNER notifications; while
 * the stream is down it polls every 30 s like the other admin monitors.
 */
export function useAdminDeliveries(params: AdminDeliveryListParams) {
  const live = useRealtimeLive()
  return useQuery({
    queryKey: adminDeliveryKeys.list(params),
    queryFn: () => listAdminDeliveries(params),
    placeholderData: keepPreviousData,
    refetchInterval: live ? false : ADMIN_MONITOR_FALLBACK_POLL_MS,
  })
}

export function useAdminDelivery(id: string) {
  return useQuery({ queryKey: adminDeliveryKeys.detail(id), queryFn: () => fetchAdminDelivery(id) })
}

/** Online partners for "Reassign" (fresh every time the dialog opens; positions move). */
export function useOnlinePartners(enabled: boolean) {
  return useQuery({
    queryKey: adminDeliveryKeys.onlinePartners(),
    queryFn: listOnlinePartners,
    enabled,
    staleTime: 0,
  })
}

/** Reassign; the monitor (lists, drawer, online partners) is re-read afterwards — also after a 409. */
export function useReassignDelivery() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: ({ id, body }: { id: string; body: ReassignRequest }) => reassignDelivery(id, body),
    onSettled: () => queryClient.invalidateQueries({ queryKey: adminDeliveryKeys.all }),
  })
}
