import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import {
  adjustStock,
  bulkAdjustStock,
  fetchInventorySummary,
  inventoryKeys,
  listInventory,
  listStockMovements,
  setLowStockThreshold,
} from './api'

/** @typedef {import('./api').InventoryListParams} InventoryListParams */

/**
 * @param {InventoryListParams} params
 * @param {{ enabled?: boolean }} [options={}]
 */
export function useVendorInventory(params, options = {}) {
  return useQuery({
    queryKey: inventoryKeys.list(params),
    queryFn: () => listInventory(params),
    placeholderData: keepPreviousData,
    enabled: options.enabled ?? true,
  })
}

export function useInventorySummary() {
  return useQuery({ queryKey: inventoryKeys.summary(), queryFn: fetchInventorySummary })
}

/**
 * One page of a product's stock ledger (newest first).
 * @param {string} productId
 * @param {number} page
 */
export function useStockMovements(productId, page) {
  return useQuery({
    queryKey: inventoryKeys.movements(productId, page),
    queryFn: () => listStockMovements(productId, page),
    placeholderData: keepPreviousData,
  })
}

/** Increase / decrease / set the stock of one product; refreshes the lists, the summary and the ledger. */
export function useAdjustStock() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: ({ productId, body }) => adjustStock(productId, body),
    onSettled: () => queryClient.invalidateQueries({ queryKey: inventoryKeys.all }),
  })
}

/** Several adjustments in one all-or-nothing request. */
export function useBulkAdjustStock() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (items) => bulkAdjustStock(items),
    onSettled: () => queryClient.invalidateQueries({ queryKey: inventoryKeys.all }),
  })
}

export function useSetLowStockThreshold() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: ({ productId, lowStockThreshold }) => setLowStockThreshold(productId, lowStockThreshold),
    onSettled: () => queryClient.invalidateQueries({ queryKey: inventoryKeys.all }),
  })
}
