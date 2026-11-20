import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import {
  cancelPriceRequest,
  fetchPricingSummary,
  listMyPriceRequests,
  listPriceHistory,
  listVendorPrices,
  setVendorPrice,
  vendorPriceKeys,
} from './api'

/** @typedef {import('./api').PriceRequestListParams} PriceRequestListParams */
/** @typedef {import('./api').VendorPriceListParams} VendorPriceListParams */

/** @param {VendorPriceListParams} params */
export function useVendorPrices(params) {
  return useQuery({
    queryKey: vendorPriceKeys.list(params),
    queryFn: () => listVendorPrices(params),
    placeholderData: keepPreviousData,
  })
}

export function usePricingSummary() {
  return useQuery({ queryKey: vendorPriceKeys.summary(), queryFn: fetchPricingSummary })
}

/**
 * One page of a product's price history (newest first).
 * @param {string} productId
 * @param {number} page
 */
export function usePriceHistory(productId, page) {
  return useQuery({
    queryKey: vendorPriceKeys.history(productId, page),
    queryFn: () => listPriceHistory(productId, page),
    placeholderData: keepPreviousData,
  })
}

/**
 * The vendor's own price-change requests, newest first.
 * @param {PriceRequestListParams} params
 */
export function useMyPriceRequests(params) {
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
    mutationFn: ({ productId, body }) => setVendorPrice(productId, body),
    onSettled: () => queryClient.invalidateQueries({ queryKey: vendorPriceKeys.all }),
  })
}

export function useCancelPriceRequest() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (id) => cancelPriceRequest(id),
    onSettled: () => queryClient.invalidateQueries({ queryKey: vendorPriceKeys.all }),
  })
}
