import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { inventoryKeys } from '../vendor-inventory/api'
import {
  createPurchaseOrder,
  createSupplier,
  deactivateSupplier,
  fetchPurchaseOrder,
  listAdminPurchaseOrders,
  listPurchaseOrders,
  listSuppliers,
  purchasingKeys,
  receivePurchaseOrder,
  transitionPurchaseOrder,
  updatePurchaseOrder,
  updateSupplier,
} from './api'

/** @typedef {import('./api').AdminPurchaseOrderListParams} AdminPurchaseOrderListParams */
/** @typedef {import('./api').PurchaseOrderListParams} PurchaseOrderListParams */

export function useSuppliers() {
  return useQuery({ queryKey: purchasingKeys.suppliers(), queryFn: listSuppliers })
}

export function useSaveSupplier() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: ({ id, body }) => (id ? updateSupplier(id, body) : createSupplier(body)),
    onSettled: () => queryClient.invalidateQueries({ queryKey: purchasingKeys.suppliers() }),
  })
}

export function useDeactivateSupplier() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (id) => deactivateSupplier(id),
    onSettled: () => queryClient.invalidateQueries({ queryKey: purchasingKeys.suppliers() }),
  })
}

/** @param {PurchaseOrderListParams} params */
export function usePurchaseOrders(params) {
  return useQuery({
    queryKey: purchasingKeys.orderList(params),
    queryFn: () => listPurchaseOrders(params),
    placeholderData: keepPreviousData,
  })
}

/** @param {string | undefined} id */
export function usePurchaseOrder(id) {
  return useQuery({
    queryKey: purchasingKeys.order(id ?? ''),
    queryFn: () => fetchPurchaseOrder(id ?? ''),
    enabled: Boolean(id),
  })
}

/** The answer goes into the detail cache; the lists refresh. */
function useStoreOrder() {
  const queryClient = useQueryClient()
  return async (order) => {
    queryClient.setQueryData(purchasingKeys.order(order.id), order)
    await queryClient.invalidateQueries({ queryKey: [...purchasingKeys.orders(), 'list'] })
  }
}

export function useSavePurchaseOrder() {
  const store = useStoreOrder()
  return useMutation({
    mutationFn: ({ id, body }) => (id ? updatePurchaseOrder(id, body) : createPurchaseOrder(body)),
    onSuccess: store,
  })
}

/** Send, cancel or close. After a 409 the order is re-read (its status changed elsewhere). */
export function usePurchaseOrderTransition() {
  const queryClient = useQueryClient()
  const store = useStoreOrder()
  return useMutation({
    mutationFn: ({ id, transition }) => transitionPurchaseOrder(id, transition),
    onSuccess: store,
    onError: (_error, { id }) => queryClient.invalidateQueries({ queryKey: purchasingKeys.order(id) }),
  })
}

/** Receive goods; the vendor's stock changes too (inventory-service consumes `GoodsReceived`). */
export function useReceivePurchaseOrder() {
  const queryClient = useQueryClient()
  const store = useStoreOrder()
  return useMutation({
    mutationFn: ({ id, body, idempotencyKey }) => receivePurchaseOrder(id, body, idempotencyKey),
    onSuccess: async (order) => {
      await store(order)
      await queryClient.invalidateQueries({ queryKey: inventoryKeys.all })
    },
  })
}

/** @param {AdminPurchaseOrderListParams} params */
export function useAdminPurchaseOrders(params) {
  return useQuery({
    queryKey: purchasingKeys.adminList(params),
    queryFn: () => listAdminPurchaseOrders(params),
    placeholderData: keepPreviousData,
  })
}
