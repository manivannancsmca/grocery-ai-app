import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import {
  type AdjustmentRequest,
  adjustStock,
  type BulkAdjustmentItem,
  bulkAdjustStock,
  fetchInventorySummary,
  type InventoryListParams,
  inventoryKeys,
  listInventory,
  listStockMovements,
  setLowStockThreshold,
} from './api'

export function useVendorInventory(params: InventoryListParams, options: { enabled?: boolean } = {}) {
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

/** One page of a product's stock ledger (newest first). */
export function useStockMovements(productId: string, page: number) {
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
    mutationFn: ({ productId, body }: { productId: string; body: AdjustmentRequest }) => adjustStock(productId, body),
    onSettled: () => queryClient.invalidateQueries({ queryKey: inventoryKeys.all }),
  })
}

/** Several adjustments in one all-or-nothing request. */
export function useBulkAdjustStock() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (items: BulkAdjustmentItem[]) => bulkAdjustStock(items),
    onSettled: () => queryClient.invalidateQueries({ queryKey: inventoryKeys.all }),
  })
}

export function useSetLowStockThreshold() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: ({ productId, lowStockThreshold }: { productId: string; lowStockThreshold: number }) =>
      setLowStockThreshold(productId, lowStockThreshold),
    onSettled: () => queryClient.invalidateQueries({ queryKey: inventoryKeys.all }),
  })
}
