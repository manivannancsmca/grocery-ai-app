import { useQuery } from '@tanstack/react-query'
import { useRealtimeLive } from '../../shared/realtime/useRealtime'
import { fetchTracking, trackingKeys } from './api'

/**
 * Tracking follows the live stream: DELIVERY_LOCATION moves the partner in the cache, and PARTNER_ASSIGNED /
 * OUT_FOR_DELIVERY / ORDER_DELIVERED re-read it (the realtime layer). While the stream is down, a 30 s poll.
 */
export const TRACKING_FALLBACK_POLL_MS = 30_000

/**
 * @param {string} orderId
 * @param {boolean} enabled
 */
export function useOrderTracking(orderId, enabled) {
  const live = useRealtimeLive()
  return useQuery({
    queryKey: trackingKeys.detail(orderId),
    queryFn: () => fetchTracking(orderId),
    enabled: enabled && orderId !== '',
    staleTime: 0,
    refetchInterval: live ? false : TRACKING_FALLBACK_POLL_MS,
  })
}
