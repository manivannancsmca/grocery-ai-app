import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import {
  cancelPriceRequest,
  fetchPricingSummary,
  listMyPriceRequests,
  listPriceHistory,
  listVendorPrices,
  type PriceRequest,
  type PriceRequestListParams,
  setVendorPrice,
  type VendorPriceListParams,
  vendorPriceKeys,
} from './api'

export function useVendorPrices(params: VendorPriceListParams) {
  return useQuery({
    queryKey: vendorPriceKeys.list(params),
    queryFn: () => listVendorPrices(params),
    placeholderData: keepPreviousData,
  })
}

export function usePricingSummary() {
  return useQuery({ queryKey: vendorPriceKeys.summary(), queryFn: fetchPricingSummary })
}

/** One page of a product's price history (newest first). */
export function usePriceHistory(productId: string, page: number) {
  return useQuery({
    queryKey: vendorPriceKeys.history(productId, page),
    queryFn: () => listPriceHistory(productId, page),
    placeholderData: keepPreviousData,
  })
}

/** The vendor's own price-change requests, newest first. */
export function useMyPriceRequests(params: PriceRequestListParams) {
  return useQuery({
    queryKey: vendorPriceKeys.requests(params),
    queryFn: () => listMyPriceRequests(params),
    placeholderData: keepPreviousData,
  })
}

/** Sets a price (applied now, or sent for approval); refreshes every price list, summary and request list. */
export function useSetPrice() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: ({ productId, body }: { productId: string; body: PriceRequest }) => setVendorPrice(productId, body),
    onSettled: () => queryClient.invalidateQueries({ queryKey: vendorPriceKeys.all }),
  })
}

export function useCancelPriceRequest() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (id: string) => cancelPriceRequest(id),
    onSettled: () => queryClient.invalidateQueries({ queryKey: vendorPriceKeys.all }),
  })
}
