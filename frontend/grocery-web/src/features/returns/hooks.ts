import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { orderKeys } from '../orders/api'
import { fetchMyReturns, fetchOrderReturns, type MyReturnParams, requestReturn, type ReturnRequestBody, returnKeys } from './api'

export function useMyReturns(params: MyReturnParams) {
  return useQuery({
    queryKey: returnKeys.mineList(params),
    queryFn: () => fetchMyReturns(params),
    placeholderData: keepPreviousData,
  })
}

/** One order's return requests in full (items, refunds, the store's comment). */
export function useOrderReturns(orderId: string, options: { enabled?: boolean } = {}) {
  return useQuery({
    queryKey: returnKeys.forOrder(orderId),
    queryFn: () => fetchOrderReturns(orderId),
    enabled: orderId !== '' && (options.enabled ?? true),
  })
}

/** The answer carries the server's refund; the order (its `returnable` and `returns`) and the return lists are re-read. */
export function useRequestReturn(orderId: string) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (body: ReturnRequestBody) => requestReturn(orderId, body),
    onSettled: () => {
      void queryClient.invalidateQueries({ queryKey: orderKeys.detail(orderId) })
      void queryClient.invalidateQueries({ queryKey: orderKeys.lists() })
      void queryClient.invalidateQueries({ queryKey: returnKeys.all })
    },
  })
}
