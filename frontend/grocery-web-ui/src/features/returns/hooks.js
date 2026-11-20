import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { orderKeys } from '../orders/api'
import { fetchMyReturns, fetchOrderReturns, requestReturn, returnKeys } from './api'

/** @typedef {import('./api').MyReturnParams} MyReturnParams */

/** @param {MyReturnParams} params */
export function useMyReturns(params) {
  return useQuery({
    queryKey: returnKeys.mineList(params),
    queryFn: () => fetchMyReturns(params),
    placeholderData: keepPreviousData,
  })
}

/**
 * One order's return requests in full (items, refunds, the store's comment).
 * @param {string} orderId
 * @param {{ enabled?: boolean }} [options={}]
 */
export function useOrderReturns(orderId, options = {}) {
  return useQuery({
    queryKey: returnKeys.forOrder(orderId),
    queryFn: () => fetchOrderReturns(orderId),
    enabled: orderId !== '' && (options.enabled ?? true),
  })
}

/**
 * The answer carries the server's refund; the order (its `returnable` and `returns`) and the return lists are re-read.
 * @param {string} orderId
 */
export function useRequestReturn(orderId) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (body) => requestReturn(orderId, body),
    onSettled: () => {
      void queryClient.invalidateQueries({ queryKey: orderKeys.detail(orderId) })
      void queryClient.invalidateQueries({ queryKey: orderKeys.lists() })
      void queryClient.invalidateQueries({ queryKey: returnKeys.all })
    },
  })
}
